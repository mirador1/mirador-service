#!/usr/bin/env bash
# =============================================================================
# run.sh — unified entry point for local development and CI tasks
#
# Usage:
#   ./run.sh <command>
#
# Infrastructure commands (Docker Compose):
#   db        start PostgreSQL
#   kafka     start Kafka
#   obs       start observability stack (Grafana/LGTM, Mimir, Pyroscope)
#   app       start Spring application (local Maven)
#   all       start everything (db + kafka + obs + app)
#   restart   stop + clean restart of all containers, then start app
#   stop      stop app + all containers (infra, obs)
#   nuke      full cleanup — containers, volumes, build artifacts
#   status    check status of all services and print URLs
#   simulate  run HTTP traffic simulation (default: 60 iterations, 2s pause)
#
# GitLab CI/CD commands (runners execute on this machine, not gitlab.com shared runners):
#   runner         start GitLab Runner (docker-compose.runner.yml)
#   runner-stop    stop GitLab Runner
#   register-cloud register runner against gitlab.com  (./scripts/register-runner.sh cloud <TOKEN>)
#
# Quality commands (mirror CI pipeline — no Docker needed except for 'integration'):
#   lint      Dockerfile linting with hadolint
#   test      unit tests only (fast, no Docker)
#   check     alias for test
#   integration  integration tests + SpotBugs + JaCoCo (needs Docker for Testcontainers)
#   verify    full pipeline: lint + test + integration
#   ci        alias for verify
#   package   build the fat JAR (run verify first)
#   docker    build local JVM Docker image
#   clean     wipe target/
#   site      generate Maven quality reports and serve them at http://localhost:8084
#             Runs: mvn verify && mvn site → starts the nginx maven-site container
#             The site is regenerated daily in CI (REPORT_PIPELINE=true schedule)
#
# Setup:
#   ./run.sh install-tools    install hadolint + lefthook via Homebrew
# =============================================================================
set -e

MVNW="./mvnw"
MAVEN="$MVNW --batch-mode --errors --no-transfer-progress"
IMAGE="mirador:local"

# Ensure Docker is running for commands that need it
ensure_docker() {
  if docker info >/dev/null 2>&1; then
    return
  fi
  echo "Docker is not running — attempting to start..."
  case "$(uname -s)" in
    Darwin)
      open -a Docker
      ;;
    Linux)
      if command -v systemctl &>/dev/null; then
        sudo systemctl start docker
      else
        echo "ERROR: Cannot start Docker automatically. Please start it manually."
        exit 1
      fi
      ;;
    *)
      echo "ERROR: Unsupported OS. Please start Docker manually."
      exit 1
      ;;
  esac
  echo -n "Waiting for Docker"
  while ! docker info >/dev/null 2>&1; do
    echo -n "."
    sleep 2
  done
  echo " ready!"
}

case "$1" in

  # ---------------------------------------------------------------------------
  # Infrastructure
  # ---------------------------------------------------------------------------

  db)
    ensure_docker
    echo "Starting PostgreSQL..."
    docker compose up -d db
    ;;

  kafka)
    ensure_docker
    echo "Starting Kafka..."
    docker compose up -d kafka
    ;;

  obs)
    ensure_docker
    echo "Starting observability stack..."
    docker compose -f docker-compose.observability.yml up -d
    ;;

  runner)
    ensure_docker
    echo "Starting GitLab Runner..."
    docker compose -f docker-compose.runner.yml up -d
    echo ""
    echo "  Runner is up. Register it against gitlab.com with:"
    echo "    ./run.sh register-cloud <TOKEN>"
    echo "  Get the token: gitlab.com → Project → Settings → CI/CD → Runners → New project runner"
    ;;

  runner-stop)
    echo "Stopping GitLab Runner..."
    docker compose -f docker-compose.runner.yml down
    ;;

  register-cloud)
    TOKEN="${2:-}"
    if [ -z "$TOKEN" ]; then
      echo "Usage: ./run.sh register-cloud <TOKEN>"
      echo ""
      echo "  Get the token from: gitlab.com → Project → Settings → CI/CD → Runners"
      exit 1
    fi
    ./scripts/register-runner.sh cloud "$TOKEN"
    ;;

  app)
    echo "Starting Spring app (local)..."
    $MVNW spring-boot:run
    ;;


  all)
    ensure_docker
    echo "Starting everything..."
    # Start infra services only (not the app container — we run locally via Maven)
    docker compose up -d db kafka redis ollama keycloak cloudbeaver kafka-ui redisinsight
    # Start observability stack
    docker compose -f docker-compose.observability.yml up -d
    # Wait for DB to be healthy before starting the app
    echo -n "Waiting for PostgreSQL"
    until docker inspect -f '{{.State.Health.Status}}' postgres-demo 2>/dev/null | grep -q healthy; do
      echo -n "."
      sleep 2
    done
    echo " ready!"
    $MVNW spring-boot:run
    ;;

  simulate)
    echo "Starting traffic simulation..."
    ./scripts/simulate-traffic.sh "${2:-60}" "${3:-2}"
    ;;

  restart)
    ensure_docker
    echo "Restarting everything (clean)..."
    # Kill the running Spring app (target Java process only, not Docker)
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    # Stop all containers (both compose files)
    docker compose -f docker-compose.observability.yml down
    docker compose down
    # Start infra (not the app container — we run locally via Maven)
    docker compose up -d db kafka redis ollama keycloak cloudbeaver kafka-ui redisinsight
    # Start observability stack
    docker compose -f docker-compose.observability.yml up -d
    # Wait for DB to be healthy before starting the app
    echo -n "Waiting for PostgreSQL"
    until docker inspect -f '{{.State.Health.Status}}' postgres-demo 2>/dev/null | grep -q healthy; do
      echo -n "."
      sleep 2
    done
    echo " ready!"
    echo "Infrastructure ready. Starting app..."
    $MVNW spring-boot:run
    ;;

  nuke)
    ensure_docker
    echo "Full cleanup — removing containers, volumes, and build artifacts..."
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    docker compose down -v
    docker compose -f docker-compose.observability.yml down -v
    docker compose -f docker-compose.runner.yml down -v 2>/dev/null || true
    $MAVEN clean
    echo "Done. Run './run.sh all' to start from scratch."
    ;;

  stop)
    echo "Stopping everything..."
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    docker compose down
    docker compose -f docker-compose.observability.yml down
    docker compose -f docker-compose.runner.yml down 2>/dev/null || true
    ;;

  # ---------------------------------------------------------------------------
  # Quality / CI
  # ---------------------------------------------------------------------------

  lint)
    echo "Running Dockerfile linting..."
    if command -v hadolint &>/dev/null; then
      hadolint Dockerfile
    else
      echo "hadolint not found — running via Docker (install with: ./run.sh install-tools)"
      docker run --rm -i hadolint/hadolint < Dockerfile
    fi
    ;;

  test|check)
    echo "Running unit tests (fast, no Docker)..."
    $MAVEN test
    ;;

  integration)
    echo "Running integration tests + SpotBugs + JaCoCo (needs Docker)..."
    $MAVEN verify -Dsurefire.skip=true
    ;;

  verify|ci)
    echo "Running full pipeline: lint + unit + integration..."
    "$0" lint
    "$0" test
    "$0" integration
    ;;

  package)
    echo "Building fat JAR (skipping tests — run verify first)..."
    $MAVEN -DskipTests package
    ;;

  docker)
    echo "Building local JVM Docker image..."
    docker build -t "$IMAGE" .
    echo ""
    echo "Image built: $IMAGE"
    echo "Run with: docker compose up -d"
    ;;

  security-check)
    echo "Running OWASP Dependency-Check (CVE scan)..."
    $MAVEN dependency-check:check
    echo "Report: target/dependency-check-report.html"
    ;;

  site)
    # Generate the full Maven quality report site and serve it via nginx on port 8084.
    #
    # Why this exists:
    #   The CI report schedule (REPORT_PIPELINE=true) generates the site daily and pushes
    #   it to the reports/ branch. Locally, use this command to regenerate on demand —
    #   useful when working on test coverage, SpotBugs fixes, or Javadoc improvements.
    #
    # What it generates (target/site/):
    #   Surefire test results · Failsafe integration test results
    #   JaCoCo coverage report · SpotBugs analysis · Javadoc
    #   Mutation testing (PIT) report at target/site/pit-reports/index.html
    #   Project info: dependencies, licenses, team, source xref
    #   Note: OWASP and pitest HTML are copied by the antrun post-site phase.
    #   Without `post-site`, pit-reports/ and dependency-check-report.html won't appear.
    ensure_docker
    echo "Generating Maven quality reports (mvn verify + site)..."
    echo "  Step 1/2: mvn verify  (runs tests + collects JaCoCo/SpotBugs data)"
    $MAVEN verify -q
    echo "  Step 2/2: mvn site post-site (generates HTML + copies OWASP/pitest reports into site/)"
    $MAVEN site post-site -q
    echo ""
    echo "Starting maven-site nginx container..."
    docker compose up -d maven-site
    echo ""
    echo "  Maven Site  http://localhost:8084"
    echo "  Reports:    Surefire · Failsafe · JaCoCo · SpotBugs · Mutation Testing · Javadoc"
    echo ""
    echo "  To stop:    docker compose stop maven-site"
    echo "  To rebuild: ./run.sh site"
    ;;

  sonar-setup)
    # First-time SonarQube configuration on a fresh volume.
    # Disables force-authentication so the dashboard is accessible without login (local dev only).
    # Run once after `docker compose up -d sonarqube` when the volume is new.
    ensure_docker
    echo "Waiting for SonarQube to be ready..."
    until curl -s http://localhost:9000/api/system/status | grep -q '"status":"UP"'; do
      echo -n "."
      sleep 5
    done
    echo " ready!"
    ADMIN_PASS="${1:-admin}"
    curl -s -X POST -u "admin:${ADMIN_PASS}" \
      "http://localhost:9000/api/settings/set" \
      -d "key=sonar.forceAuthentication&value=false" > /dev/null
    echo "  Force authentication disabled — dashboard accessible without login."
    echo "  SonarQube: http://localhost:9000"
    echo ""
    echo "  Next: generate a token at http://localhost:9000/account/security"
    echo "  Then set SONAR_TOKEN=<token> in .env and run: ./run.sh sonar"
    ;;

  sonar)
    # Run SonarQube analysis against the local Docker SonarQube instance (port 9000).
    # Prerequisites:
    #   1. docker compose up -d sonarqube (wait ~2 min for first startup)
    #   2. ./run.sh sonar-setup           (disables force-auth, one-time)
    #   3. Generate a token at http://localhost:9000/account/security
    #   4. Set SONAR_TOKEN=<token> in .env
    #
    # Runs mvn verify (unit + integration tests) to produce jacoco-merged.xml.
    # Integration tests are NOT skipped — CustomerController, AuthController and
    # messaging classes are only exercised by @SpringBootTest ITests. Skipping them
    # would yield ~32% coverage instead of ~80%.
    if [ -z "$SONAR_TOKEN" ]; then
      echo "Error: SONAR_TOKEN is not set in .env."
      echo "Generate one at http://localhost:9000/account/security"
      exit 1
    fi
    echo "Running tests + integration tests + SonarQube analysis (this takes ~3 min)..."
    $MAVEN verify -q
    $MAVEN sonar:sonar \
      -Dsonar.token="$SONAR_TOKEN" \
      -Dsonar.host.url=http://localhost:9000
    echo ""
    echo "  SonarQube report: http://localhost:9000/projects"
    ;;

  # ─── Local Kubernetes test (kind) ──────────────────────────────────────────
  # Deploys the full stack into a local kind cluster to validate K8s manifests
  # before pushing to GKE. Requires: kind, kubectl, Docker.
  #
  # App URL after deploy: http://mirador.127.0.0.1.nip.io:8090
  # nip.io resolves *.127.0.0.1.nip.io → 127.0.0.1 (no /etc/hosts needed).
  k8s-local)
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
    ;;

  k8s-local-delete)
    KIND_CLUSTER="mirador"
    echo "Deleting kind cluster '${KIND_CLUSTER}' and all its resources..."
    kind delete cluster --name "${KIND_CLUSTER}"
    echo "Cluster deleted."
    ;;

  # ─── GCP infrastructure provisioning via Terraform ─────────────────────────
  # Provisions GKE Autopilot + Cloud SQL + Memorystore (Redis) + VPC.
  # Requires: gcloud CLI, Terraform ≥ 1.8, a GCP project, and credentials.
  #
  # One-time setup:
  #   1. Install tools: brew install google-cloud-sdk terraform
  #   2. Authenticate: gcloud auth application-default login
  #   3. Set project: gcloud config set project <PROJECT_ID>
  #   4. Enable APIs: ./run.sh gcp-enable-apis
  #   5. Create TF state bucket: ./run.sh gcp-tf-bucket
  #   6. Copy deploy/terraform/gcp/terraform.tfvars.example → terraform.tfvars and fill in values
  #   7. ./run.sh tf-plan   (preview changes)
  #   8. ./run.sh tf-apply  (apply changes)
  gcp-enable-apis)
    command -v gcloud >/dev/null 2>&1 || { echo "❌  gcloud not found — brew install google-cloud-sdk"; exit 1; }
    PROJECT=$(gcloud config get-value project 2>/dev/null)
    [ -z "$PROJECT" ] && { echo "❌  No GCP project set. Run: gcloud config set project <PROJECT_ID>"; exit 1; }
    echo "Enabling required GCP APIs for project: ${PROJECT}"
    gcloud services enable \
      container.googleapis.com \
      sqladmin.googleapis.com \
      redis.googleapis.com \
      servicenetworking.googleapis.com \
      managedkafka.googleapis.com \
      --project="${PROJECT}"
    echo "✅  APIs enabled."
    ;;

  gcp-tf-bucket)
    command -v gcloud >/dev/null 2>&1 || { echo "❌  gcloud not found — brew install google-cloud-sdk"; exit 1; }
    command -v gsutil >/dev/null 2>&1 || { echo "❌  gsutil not found — part of google-cloud-sdk"; exit 1; }
    PROJECT=$(gcloud config get-value project 2>/dev/null)
    [ -z "$PROJECT" ] && { echo "❌  No GCP project set."; exit 1; }
    REGION="${1:-europe-west1}"
    BUCKET="${PROJECT}-tf-state"
    echo "Creating Terraform state bucket: gs://${BUCKET} (region: ${REGION})"
    gsutil mb -p "${PROJECT}" -l "${REGION}" "gs://${BUCKET}" 2>/dev/null || echo "Bucket already exists."
    gsutil versioning set on "gs://${BUCKET}"
    echo "✅  GCS bucket ready: gs://${BUCKET}"
    ;;

  tf-plan)
    command -v terraform >/dev/null 2>&1 || { echo "❌  terraform not found — brew install terraform"; exit 1; }
    cd deploy/terraform/gcp
    [ ! -f terraform.tfvars ] && { echo "❌  deploy/terraform/gcp/terraform.tfvars not found. Copy from terraform.tfvars.example."; exit 1; }
    PROJECT=$(grep project_id terraform.tfvars | sed 's/.*= *"\(.*\)"/\1/')
    terraform init -backend-config="bucket=${PROJECT}-tf-state" -backend-config="prefix=mirador/gcp" -input=false
    terraform plan
    ;;

  tf-apply)
    command -v terraform >/dev/null 2>&1 || { echo "❌  terraform not found — brew install terraform"; exit 1; }
    cd deploy/terraform/gcp
    [ ! -f terraform.tfvars ] && { echo "❌  deploy/terraform/gcp/terraform.tfvars not found."; exit 1; }
    PROJECT=$(grep project_id terraform.tfvars | sed 's/.*= *"\(.*\)"/\1/')
    terraform init -backend-config="bucket=${PROJECT}-tf-state" -backend-config="prefix=mirador/gcp" -input=false
    terraform apply
    ;;

  tf-destroy)
    command -v terraform >/dev/null 2>&1 || { echo "❌  terraform not found"; exit 1; }
    echo "⚠️  This will destroy all GCP infrastructure (GKE cluster, Cloud SQL, Redis)."
    echo "    Press Ctrl+C to abort, or Enter to continue..."
    read -r
    cd deploy/terraform/gcp
    PROJECT=$(grep project_id terraform.tfvars | sed 's/.*= *"\(.*\)"/\1/')
    terraform init -backend-config="bucket=${PROJECT}-tf-state" -backend-config="prefix=mirador/gcp" -input=false
    terraform destroy
    ;;

  gke-infra-setup)
    # One-time setup of GKE Autopilot cluster infrastructure prerequisites.
    # Installs ingress-nginx + cert-manager, then applies the GKE Autopilot
    # compatibility patches (leader-election namespace + RBAC for kube-system lock bypass).
    #
    # GKE Autopilot denies both cert-manager controller and cainjector from creating
    # leader-election Lease locks in kube-system ("managed namespace" policy).
    # Fix: redirect leader election to the cert-manager namespace itself.
    #
    # Run once after cluster creation (before first deploy:gke pipeline run).
    command -v kubectl >/dev/null 2>&1 || { echo "❌  kubectl not found — brew install kubectl"; exit 1; }
    echo "📦  Installing ingress-nginx..."
    kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
    echo "📦  Installing cert-manager..."
    kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
    echo "⏳  Waiting for cert-manager pods to be ready..."
    kubectl wait --for=condition=available deployment/cert-manager \
      deployment/cert-manager-webhook deployment/cert-manager-cainjector \
      -n cert-manager --timeout=120s
    echo "🔧  Applying GKE Autopilot RBAC patches (leader-election in cert-manager namespace)..."
    kubectl apply -f deploy/kubernetes/gke/cert-manager-gke-fix.yaml
    kubectl patch deployment cert-manager -n cert-manager --type='json' \
      -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--leader-election-namespace=cert-manager"}]'
    kubectl patch deployment cert-manager-cainjector -n cert-manager --type='json' \
      -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--leader-election-namespace=cert-manager"}]'
    kubectl rollout status deployment/cert-manager deployment/cert-manager-cainjector \
      -n cert-manager --timeout=60s
    echo "🔑  Creating letsencrypt-prod ClusterIssuer..."
    kubectl apply -f - <<'ISSUER'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: benoitbesson@gmail.com
    privateKeySecretRef:
      name: letsencrypt-prod-account-key
    solvers:
      - http01:
          ingress:
            ingressClassName: nginx
ISSUER
    echo "⏳  Waiting for ClusterIssuer to be ready..."
    for i in $(seq 1 12); do
      READY=$(kubectl get clusterissuer letsencrypt-prod -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)
      if [ "$READY" = "True" ]; then
        echo "✅  ClusterIssuer letsencrypt-prod is Ready"
        break
      fi
      echo "    still waiting... ($i/12)"
      sleep 5
    done
    echo "✅  GKE infra setup complete."
    echo "    Run 'deploy:gke' pipeline or push to main to deploy the application."
    ;;

  clean)
    echo "Cleaning build artifacts..."
    $MAVEN clean
    ;;

  install-tools)
    echo "Installing hadolint + lefthook via Homebrew..."
    brew install hadolint lefthook
    lefthook install
    echo ""
    echo "Tools installed. Git pre-push hook is now active."
    echo "Every 'git push' will automatically run './run.sh check' (unit tests)."
    ;;

  status)
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║                   mirador status                   ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""

    # Docker
    if docker info >/dev/null 2>&1; then
      echo "  Docker              ✅ running"
    else
      echo "  Docker              ❌ not running"
    fi

    # Spring Boot app
    if pgrep -f 'MiradorApplication' >/dev/null 2>&1; then
      echo "  Spring Boot app     ✅ running (PID $(pgrep -f 'MiradorApplication' | head -1))"
    else
      echo "  Spring Boot app     ❌ not running"
    fi

    # Health check
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
      echo "  Health check        ✅ UP (http://localhost:8080)"
    else
      echo "  Health check        ❌ DOWN (HTTP $HTTP_CODE)"
    fi

    echo ""
    echo "  ── Infrastructure ──────────────────────────────────────────"
    # Check each container
    for svc in postgres-demo kafka-demo redis-demo ollama keycloak; do
      STATUS=$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo "missing")
      HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "")
      if [ "$STATUS" = "running" ]; then
        LABEL="✅ $STATUS"
        [ -n "$HEALTH" ] && LABEL="$LABEL ($HEALTH)"
      else
        LABEL="❌ $STATUS"
      fi
      printf "  %-22s%s\n" "$svc" "$LABEL"
    done

    echo ""
    echo "  ── Admin tools ─────────────────────────────────────────────"
    for svc in cloudbeaver kafka-ui redisinsight maven-site sonarqube compodoc; do
      STATUS=$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo "missing")
      if [ "$STATUS" = "running" ]; then
        LABEL="✅ $STATUS"
      else
        LABEL="❌ $STATUS"
      fi
      printf "  %-22s%s\n" "$svc" "$LABEL"
    done

    echo ""
    echo "  ── Observability ────────────────────────────────────────────"
    # LGTM all-in-one: Grafana + OTel Collector + Loki + Tempo + Mimir + Pyroscope
    for svc in customerservice-lgtm customerservice-cors-proxy customerservice-docker-proxy; do
      STATUS=$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo "missing")
      if [ "$STATUS" = "running" ]; then
        LABEL="✅ $STATUS"
      else
        LABEL="⬚  $STATUS"
      fi
      SHORT=$(echo "$svc" | sed 's/customerservice-//')
      printf "  %-22s%s\n" "$SHORT" "$LABEL"
    done

    echo ""
    echo "  ── CI/CD ────────────────────────────────────────────────────"
    RUNNER_STATUS=$(docker inspect -f '{{.State.Status}}' "gitlab-runner" 2>/dev/null || echo "missing")
    if [ "$RUNNER_STATUS" = "running" ]; then
      RUNNER_LABEL="✅ $RUNNER_STATUS"
    else
      RUNNER_LABEL="⬚  $RUNNER_STATUS"
    fi
    printf "  %-22s%s\n" "gitlab-runner" "$RUNNER_LABEL"

    echo ""
    echo "  ── URLs ─────────────────────────────────────────────────────"
    echo "  App           http://localhost:8080"
    echo "  Swagger       http://localhost:8080/swagger-ui.html"
    echo "  pgAdmin       http://localhost:5050"
    echo "  Kafka UI      http://localhost:9080"
    echo "  RedisInsight  http://localhost:5540"
    echo "  Keycloak      http://localhost:9090"
    echo "  Grafana       http://localhost:3000  (Traces · Logs · Metrics · Profiles)"
    echo "  Mimir API     http://localhost:9091  (Prometheus-compatible metrics query)"
    echo "  Maven Site    http://localhost:8084  (run './run.sh site' to generate)"
    echo "  Compodoc      http://localhost:8086  (run 'cd ../mirador-ui && npm run compodoc')"
    echo "  SonarQube     http://localhost:9000  (run './run.sh sonar' after setting SONAR_TOKEN in .env)"
    echo ""
    ;;

  *)
    echo ""
    echo "Usage: ./run.sh <command>"
    echo ""
    echo "Infrastructure:"
    echo "  db            start PostgreSQL"
    echo "  kafka         start Kafka"
    echo "  obs           start observability stack"
    echo "  app           start Spring app (local)"
      echo "  all           start everything (infra + obs + app)"
    echo "  restart       stop + restart everything (keeps data)"
    echo "  simulate      run traffic simulation (default: 60 iterations, 2s pause)"
    echo "  stop          stop app + all containers"
    echo "  nuke          full cleanup — containers, volumes, build artifacts"
    echo "  status        check status of all services"
    echo ""
    echo "GitLab CI/CD (runs locally — zero gitlab.com shared-runner minutes):"
    echo "  runner         start GitLab Runner container"
    echo "  runner-stop    stop GitLab Runner"
    echo "  register-cloud <TOKEN>  register runner against gitlab.com"
    echo ""
    echo "Quality / CI:"
    echo "  check         unit tests only — fast, no Docker required"
    echo "  test          alias for check"
    echo "  verify        full pipeline: lint + unit + integration"
    echo "  ci            alias for verify"
    echo "  lint          Dockerfile linting (hadolint)"
    echo "  integration   IT + SpotBugs + JaCoCo (needs Docker)"
    echo "  package       build fat JAR — skips tests (run verify first)"
    echo "  docker        build local JVM Docker image tagged '$IMAGE'"
    echo "  security-check OWASP Dependency-Check (CVE scan)"
    echo "  site          generate Maven reports + serve at http://localhost:8084"
    echo "  sonar-setup   first-time SonarQube config (disable force-auth)"
    echo "  sonar         run SonarQube analysis (needs SONAR_TOKEN in .env)"
    echo "  clean         remove target/"
    echo "  install-tools install hadolint + lefthook via Homebrew"
    echo ""
    echo "Kubernetes:"
    echo "  k8s-local        deploy full stack to local kind cluster (http://mirador.127.0.0.1.nip.io:8090)"
    echo "  k8s-local-delete delete the local kind cluster"
    echo ""
    echo "GCP / Terraform:"
    echo "  gcp-enable-apis  enable required GCP APIs (container, sqladmin, redis, managedkafka)"
    echo "  gcp-tf-bucket    create GCS bucket for Terraform remote state"
    echo "  tf-plan          terraform plan (preview infra changes)"
    echo "  tf-apply         terraform apply (provision GKE + Cloud SQL + Redis)"
    echo "  tf-destroy       terraform destroy (tear down all GCP infra)"
    echo "  gke-infra-setup  one-time: install ingress-nginx + cert-manager on GKE (with Autopilot patches)"
    echo ""
    ;;
esac
