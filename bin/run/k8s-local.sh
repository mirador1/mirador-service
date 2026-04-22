#!/usr/bin/env bash
# bin/run/k8s-local.sh — implements `./run.sh k8s-local`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    command -v kind >/dev/null 2>&1    || { echo "❌  kind not installed — run: brew install kind"; exit 1; }
    command -v kubectl >/dev/null 2>&1 || { echo "❌  kubectl not installed — run: brew install kubectl"; exit 1; }
    ensure_docker

    KIND_CLUSTER="mirador"
    K8S_HOST="mirador.127.0.0.1.nip.io"
    # IMAGE_REGISTRY matches the ${IMAGE_REGISTRY}/backend:${IMAGE_TAG} pattern in manifests.
    # For kind, no external registry is used — images are loaded directly via `kind load`.
    export IMAGE_REGISTRY="mirador"
    export IMAGE_TAG="local"
    export UI_IMAGE_TAG="local"
    export K8S_HOST
    # HTTP for local (no TLS); production uses https:// set by the CI deploy job
    export CORS_ALLOWED_ORIGINS="http://${K8S_HOST}:8090"
    # Must match ${IMAGE_REGISTRY}/backend:${IMAGE_TAG} and ${IMAGE_REGISTRY}/frontend:${UI_IMAGE_TAG}
    BE_IMAGE="mirador/backend:local"
    FE_IMAGE="mirador/frontend:local"

    # ── 1. Create cluster (idempotent) ────────────────────────────────────
    if kind get clusters 2>/dev/null | grep -q "^${KIND_CLUSTER}$"; then
      echo "Kind cluster '${KIND_CLUSTER}' already exists — reusing."
    else
      echo "Creating kind cluster '${KIND_CLUSTER}' (ports 8090+8443 → ingress)..."
      kind create cluster --name "${KIND_CLUSTER}" --config deploy/kubernetes/kind-config.yaml
    fi
    kubectl config use-context "kind-${KIND_CLUSTER}"

    # ── 2. Install nginx-ingress controller (kind-specific build) ─────────
    echo "Installing nginx-ingress controller..."
    kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
    echo "Waiting for ingress controller pod to be ready (up to 120 s)..."
    kubectl wait --namespace ingress-nginx \
      --for=condition=ready pod \
      --selector=app.kubernetes.io/component=controller \
      --timeout=120s

    # ── 3. Build Docker images ────────────────────────────────────────────
    echo "Building backend image ${BE_IMAGE}..."
    docker build -t "${BE_IMAGE}" . -q
    echo "Building frontend image ${FE_IMAGE}..."
    docker build -t "${FE_IMAGE}" ../mirador-ui/ -q

    # ── 4. Load images into kind (no external registry needed) ────────────
    echo "Loading images into kind cluster (this copies layer tarballs)..."
    kind load docker-image "${BE_IMAGE}" --name "${KIND_CLUSTER}"
    kind load docker-image "${FE_IMAGE}" --name "${KIND_CLUSTER}"

    # ── 5. Create namespaces ──────────────────────────────────────────────
    kubectl create namespace app   --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace infra --dry-run=client -o yaml | kubectl apply -f -

    # ── 6. Create secrets (use defaults if not set in environment) ────────
    # These are LOCAL DEV ONLY values — never reuse in production.
    DB_PASSWORD="${DB_PASSWORD:-localdev-pg-pass}"
    JWT_SECRET="${JWT_SECRET:-localdev-jwt-secret-32charss!}"
    API_KEY="${API_KEY:-localdev-api-key}"
    kubectl create secret generic mirador-secrets \
      --from-literal=DB_PASSWORD="${DB_PASSWORD}" \
      --from-literal=JWT_SECRET="${JWT_SECRET}" \
      --from-literal=API_KEY="${API_KEY}" \
      --namespace=app --dry-run=client -o yaml | kubectl apply -f -

    # ── 7. Deploy infrastructure (PostgreSQL, Redis, Kafka) ───────────────
    echo "Deploying infra (PostgreSQL, Redis, Kafka)..."
    for f in deploy/kubernetes/namespace.yaml deploy/kubernetes/stateful/postgres.yaml deploy/kubernetes/stateful/redis.yaml deploy/kubernetes/stateful/kafka.yaml; do
      [ -f "$f" ] && envsubst < "$f" | kubectl apply -f -
    done

    # ── 8. Deploy backend ─────────────────────────────────────────────────
    echo "Deploying backend..."
    for f in deploy/kubernetes/backend/configmap.yaml deploy/kubernetes/backend/service.yaml deploy/kubernetes/backend/hpa.yaml; do
      [ -f "$f" ] && envsubst < "$f" | kubectl apply -f -
    done
    # imagePullPolicy: IfNotPresent → use the locally-loaded image, do NOT try to pull
    envsubst < deploy/kubernetes/backend/deployment.yaml \
      | sed 's/imagePullPolicy: Always/imagePullPolicy: IfNotPresent/g' \
      | kubectl apply -f -

    # ── 9. Deploy frontend ────────────────────────────────────────────────
    echo "Deploying frontend..."
    for f in deploy/kubernetes/frontend/service.yaml; do
      [ -f "$f" ] && envsubst < "$f" | kubectl apply -f -
    done
    envsubst < deploy/kubernetes/frontend/deployment.yaml \
      | sed 's/imagePullPolicy: Always/imagePullPolicy: IfNotPresent/g' \
      | kubectl apply -f -

    # ── 10. Apply local ingress (HTTP, no TLS) ────────────────────────────
    envsubst < deploy/kubernetes/local/ingress.yaml | kubectl apply -f -

    # ── 11. Wait for rollouts ─────────────────────────────────────────────
    echo "Waiting for infra pods..."
    kubectl rollout status statefulset/postgresql -n infra --timeout=120s
    kubectl rollout status deployment/kafka       -n infra --timeout=120s
    kubectl rollout status deployment/redis       -n infra --timeout=120s
    echo "Waiting for application pods (Flyway migrations run on first start)..."
    kubectl rollout status deployment/mirador -n app --timeout=300s
    kubectl rollout status deployment/customer-ui      -n app --timeout=120s

    echo ""
    echo "✅  Local Kubernetes deployment complete!"
    echo ""
    echo "  App        http://${K8S_HOST}:8090"
    echo "  Swagger    http://${K8S_HOST}:8090/api/swagger-ui.html"
    echo "  Health     http://${K8S_HOST}:8090/api/actuator/health"
    echo ""
    echo "  kubectl get pods -n app"
    echo "  kubectl get pods -n infra"
    echo "  kubectl logs -n app deployment/mirador -f"
    echo ""
    echo "  To delete the cluster: ./run.sh k8s-local-delete"
