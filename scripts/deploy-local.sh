#!/usr/bin/env bash
# =============================================================================
# deploy-local.sh — spin up a full kind cluster and deploy the application
#
# What it does:
#   1. Start a local Docker registry on localhost:5001
#   2. Create a kind cluster wired to that registry (kind-config.yaml)
#   3. Install the Nginx Ingress Controller (kind flavour)
#   4. Build the backend + frontend Docker images and push to the local registry
#   5. Create K8s namespaces and secrets
#   6. Apply all manifests (envsubst substitutes variables)
#   7. Wait for all deployments to become ready
#   8. Print access URLs
#
# Prerequisites (install once):
#   brew install kind kubectl
#   # Docker Desktop must be running
#
# Usage:
#   ./scripts/deploy-local.sh                  # deploy everything
#   ./scripts/deploy-local.sh --skip-build     # re-deploy without rebuilding images
#   ./scripts/deploy-local.sh --delete         # delete the cluster
# =============================================================================

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
CLUSTER_NAME="customer-service"
REGISTRY_NAME="registry"
REGISTRY_PORT="5001"
IMAGE_REGISTRY="localhost:${REGISTRY_PORT}"
IMAGE_TAG="local"
UI_IMAGE_TAG="local"
K8S_HOST="localhost"           # hostname used in Ingress rules
HTTP_PORT="8090"               # host port mapped to cluster's port 80

# Absolute path to the backend project root (where this script lives in scripts/)
BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Absolute path to the frontend project (adjust if your workspace layout differs)
FRONTEND_DIR="${FRONTEND_DIR:-$(cd "$BACKEND_DIR/../../js/customer-observability-ui" 2>/dev/null && pwd || echo "")}"

# Local secrets — safe for dev, never use in production
DB_PASSWORD="demo"
JWT_SECRET="local-dev-jwt-secret-min-32-chars-long"
API_KEY="local-dev-api-key"

# ── Colours ───────────────────────────────────────────────────────────────────
BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${BLUE}▶  $*${NC}"; }
ok()   { echo -e "${GREEN}✓  $*${NC}"; }
warn() { echo -e "${YELLOW}⚠  $*${NC}"; }
die()  { echo -e "${RED}✗  $*${NC}" >&2; exit 1; }

# ── Argument parsing ──────────────────────────────────────────────────────────
SKIP_BUILD=false
DELETE=false
for arg in "$@"; do
  case $arg in
    --skip-build) SKIP_BUILD=true ;;
    --delete)     DELETE=true ;;
    *) die "Unknown argument: $arg. Use --skip-build or --delete." ;;
  esac
done

# ── Delete cluster ────────────────────────────────────────────────────────────
if $DELETE; then
  log "Deleting kind cluster '$CLUSTER_NAME'..."
  kind delete cluster --name "$CLUSTER_NAME" && ok "Cluster deleted."
  log "Stopping local registry..."
  docker rm -f "$REGISTRY_NAME" 2>/dev/null && ok "Registry stopped." || warn "Registry was not running."
  exit 0
fi

# ── Prerequisites check ───────────────────────────────────────────────────────
for cmd in kind kubectl docker envsubst; do
  command -v "$cmd" &>/dev/null || die "$cmd is not installed. Run: brew install $cmd"
done

# ── 1. Local Docker registry ──────────────────────────────────────────────────
log "Starting local registry on localhost:${REGISTRY_PORT}..."
if docker ps --format '{{.Names}}' | grep -q "^${REGISTRY_NAME}$"; then
  ok "Registry already running."
else
  # Port 5001 on host → port 5000 inside the container (registry:2 listens on 5000).
  # The kind containerd mirror uses port 5000 within the Docker network (registry:5000).
  docker run -d --restart=always \
    --name "$REGISTRY_NAME" \
    -p "127.0.0.1:${REGISTRY_PORT}:5000" \
    registry:2
  ok "Registry started."
fi

# ── 2. kind cluster ───────────────────────────────────────────────────────────
log "Creating kind cluster '$CLUSTER_NAME'..."
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  ok "Cluster already exists — skipping."
else
  kind create cluster --name "$CLUSTER_NAME" --config "$BACKEND_DIR/kind-config.yaml"
  ok "Cluster created."
fi

# Connect the registry container to the kind network (idempotent)
docker network connect "kind" "$REGISTRY_NAME" 2>/dev/null || true

# Annotate the cluster so tooling knows about the local registry
kubectl apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${REGISTRY_PORT}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF

# ── 3. Nginx Ingress Controller (kind flavour) ────────────────────────────────
log "Installing Nginx Ingress Controller..."
if kubectl get deployment ingress-nginx-controller -n ingress-nginx &>/dev/null; then
  ok "Ingress controller already installed."
else
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
  log "Waiting for Ingress Controller to become ready (up to 120s)..."
  # rollout status is more reliable than `kubectl wait --for=condition=ready pod`
  # when pods haven't been scheduled yet (wait fails with "no matching resources")
  kubectl rollout status deployment/ingress-nginx-controller \
    -n ingress-nginx --timeout=120s
  ok "Ingress Controller ready."
fi

# No configuration-snippet annotations used — nginx-ingress handles WebSocket
# natively with proxy-http-version: "1.1". No controller patching needed.

# ── 4. Build + push Docker images ─────────────────────────────────────────────
if ! $SKIP_BUILD; then
  log "Building backend image..."
  docker build \
    -t "${IMAGE_REGISTRY}/customer-service:${IMAGE_TAG}" \
    "$BACKEND_DIR"
  ok "Backend image built."

  if [[ -n "$FRONTEND_DIR" && -d "$FRONTEND_DIR" ]]; then
    log "Building frontend image..."
    docker build \
      -t "${IMAGE_REGISTRY}/customer-observability-ui:${UI_IMAGE_TAG}" \
      "$FRONTEND_DIR"
    ok "Frontend image built."
  else
    warn "Frontend directory not found at '$FRONTEND_DIR'. Skipping frontend image build."
    warn "Set FRONTEND_DIR=/path/to/customer-observability-ui and re-run."
  fi
else
  warn "--skip-build: reusing existing images in local Docker cache."
fi

# Load images directly into the kind cluster's containerd.
# More reliable than the local registry for development: avoids the host→cluster
# network path and works without configuring containerd mirrors.
# Docker layer caching means repeated builds are fast (only changed layers rebuild).
log "Loading images into kind cluster..."
kind load docker-image "${IMAGE_REGISTRY}/customer-service:${IMAGE_TAG}" \
  --name "$CLUSTER_NAME"
if docker image inspect "${IMAGE_REGISTRY}/customer-observability-ui:${UI_IMAGE_TAG}" &>/dev/null; then
  kind load docker-image "${IMAGE_REGISTRY}/customer-observability-ui:${UI_IMAGE_TAG}" \
    --name "$CLUSTER_NAME"
fi
ok "Images loaded into kind."

# ── 5. Namespaces + Secrets ───────────────────────────────────────────────────
log "Creating namespaces..."
kubectl apply -f "$BACKEND_DIR/k8s/namespace.yaml"

log "Creating secrets..."
# The secret is needed in both namespaces:
#   - app:   customer-service Deployment reads DB_PASSWORD, JWT_SECRET, API_KEY
#   - infra: postgres StatefulSet reads DB_PASSWORD for POSTGRES_PASSWORD
for ns in app infra; do
  kubectl create secret generic customer-service-secrets \
    --from-literal=DB_PASSWORD="$DB_PASSWORD" \
    --from-literal=JWT_SECRET="$JWT_SECRET" \
    --from-literal=API_KEY="$API_KEY" \
    --namespace="$ns" \
    --dry-run=client -o yaml | kubectl apply -f -
done
ok "Secrets applied."

# ── 6. Apply manifests ────────────────────────────────────────────────────────
log "Applying infrastructure manifests..."
kubectl apply -f "$BACKEND_DIR/k8s/infra/postgres.yaml"
kubectl apply -f "$BACKEND_DIR/k8s/infra/redis.yaml"
kubectl apply -f "$BACKEND_DIR/k8s/infra/kafka.yaml"

log "Applying backend manifests..."
export IMAGE_REGISTRY IMAGE_TAG UI_IMAGE_TAG K8S_HOST
# Override imagePullPolicy to IfNotPresent for local registry (avoids Always pull of local images)
for f in \
  "$BACKEND_DIR/k8s/backend/configmap.yaml" \
  "$BACKEND_DIR/k8s/backend/deployment.yaml" \
  "$BACKEND_DIR/k8s/backend/service.yaml" \
  "$BACKEND_DIR/k8s/backend/hpa.yaml"; do
  envsubst < "$f" | kubectl apply -f -
done
# Switch imagePullPolicy to IfNotPresent for local registry
kubectl patch deployment customer-service -n app \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"customer-service","imagePullPolicy":"IfNotPresent"}]}}}}' \
  2>/dev/null || true

log "Applying frontend manifests..."
if kubectl get namespace app &>/dev/null; then
  for f in \
    "$BACKEND_DIR/k8s/frontend/deployment.yaml" \
    "$BACKEND_DIR/k8s/frontend/service.yaml"; do
    [[ -f "$f" ]] && envsubst < "$f" | kubectl apply -f -
  done
  kubectl patch deployment customer-ui -n app \
    -p '{"spec":{"template":{"spec":{"containers":[{"name":"customer-ui","imagePullPolicy":"IfNotPresent"}]}}}}' \
    2>/dev/null || true
fi

log "Applying Ingress..."
# No configuration-snippet in ingress.yaml anymore — apply directly.
# Disable SSL redirect below (no cert-manager/TLS in a local cluster).
envsubst < "$BACKEND_DIR/k8s/ingress.yaml" | kubectl apply -f -

# Disable HTTPS redirect (nginx-ingress defaults to redirect HTTP → HTTPS when
# a TLS block is present; not needed for local development).
kubectl annotate ingress customer-service-ingress -n app \
  nginx.ingress.kubernetes.io/ssl-redirect="false" \
  nginx.ingress.kubernetes.io/force-ssl-redirect="false" \
  --overwrite 2>/dev/null || true
# Remove the TLS spec so no redirect occurs at all
kubectl patch ingress customer-service-ingress -n app \
  --type=json \
  -p='[{"op":"remove","path":"/spec/tls"}]' 2>/dev/null || true

# Use IfNotPresent so pods don't try to pull from an external registry — the
# images are already loaded into kind's containerd via `kind load` above.
kubectl patch deployment customer-service -n app \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"customer-service","imagePullPolicy":"Never"}]}}}}' 2>/dev/null || true
kubectl patch deployment customer-ui -n app \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"customer-ui","imagePullPolicy":"Never"}]}}}}' 2>/dev/null || true

# ── 7. Wait for rollouts ──────────────────────────────────────────────────────
log "Waiting for infra (Postgres, Redis, Kafka) to be ready..."
kubectl rollout status deployment/redis     -n infra --timeout=60s
kubectl rollout status statefulset/postgresql -n infra --timeout=90s
kubectl rollout status deployment/kafka     -n infra --timeout=120s

log "Waiting for backend to be ready (may take up to 2 min for Flyway migrations)..."
kubectl rollout status deployment/customer-service -n app --timeout=180s

if kubectl get deployment customer-ui -n app &>/dev/null; then
  log "Waiting for frontend to be ready..."
  kubectl rollout status deployment/customer-ui -n app --timeout=120s
fi

# ── 8. Summary ────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  ✓ Deployment complete!${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
echo ""
echo -e "  Frontend  →  ${BLUE}http://localhost:${HTTP_PORT}${NC}"
echo -e "  API       →  ${BLUE}http://localhost:${HTTP_PORT}/api/actuator/health${NC}"
echo -e "  Swagger   →  ${BLUE}http://localhost:${HTTP_PORT}/api/swagger-ui.html${NC}"
echo ""
echo -e "  Credentials:  admin/admin  ·  user/user  ·  viewer/viewer"
echo ""
echo -e "  Tear down:  ${YELLOW}./scripts/deploy-local.sh --delete${NC}"
echo ""
