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
#   obs       start observability stack (Prometheus, Grafana, Loki, Tempo)
#   app       start Spring application (local Maven)
#   all       start everything (db + kafka + obs + app)
#   stop      stop all containers
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
#
# Setup:
#   ./run.sh install-tools    install hadolint + lefthook via Homebrew
# =============================================================================
set -e

MVNW="./mvnw"
MAVEN="$MVNW --batch-mode --errors --no-transfer-progress"
IMAGE="customer-service:local"

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

  app)
    echo "Starting Spring app (local)..."
    $MVNW spring-boot:run
    ;;

  app-profiled)
    echo "Starting Spring app with Pyroscope profiling..."
    PYROSCOPE_JAR="infra/pyroscope/pyroscope.jar"
    if [ ! -f "$PYROSCOPE_JAR" ]; then
      echo "Downloading Pyroscope Java agent..."
      mkdir -p infra/pyroscope
      curl -sL https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar -o "$PYROSCOPE_JAR"
    fi
    # Pyroscope profiles 3 dimensions:
    #   CPU  — itimer event (which methods burn CPU time)
    #   HEAP — alloc event (where memory is allocated, threshold 512KB)
    #   LOCK — lock event (where thread contention happens, threshold 10ms)
    PYROSCOPE_APPLICATION_NAME=customer-service \
    PYROSCOPE_SERVER_ADDRESS=${PYROSCOPE_SERVER_ADDRESS:-http://localhost:4040} \
    PYROSCOPE_PROFILER_EVENT=itimer \
    PYROSCOPE_PROFILER_ALLOC=512k \
    PYROSCOPE_PROFILER_LOCK=10ms \
    PYROSCOPE_LABELS="service=customer-service,env=local" \
    PYROSCOPE_UPLOAD_INTERVAL=10s \
    $MVNW spring-boot:run -Dspring-boot.run.jvmArguments="-javaagent:$PYROSCOPE_JAR"
    ;;

  all)
    ensure_docker
    echo "Starting everything..."
    # Start infra services only (not the app container — we run locally via Maven)
    docker compose up -d db kafka redis ollama keycloak pgadmin kafka-ui redisinsight
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
    ./infra/simulate-traffic.sh "${2:-60}" "${3:-2}"
    ;;

  restart)
    ensure_docker
    echo "Restarting everything (clean)..."
    # Kill the running Spring app (target Java process only, not Docker)
    pgrep -f 'CustomerServiceApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    # Stop all containers (both compose files)
    docker compose -f docker-compose.observability.yml down
    docker compose down
    # Start infra (not the app container — we run locally via Maven)
    docker compose up -d db kafka redis ollama keycloak pgadmin kafka-ui redisinsight
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
    pgrep -f 'CustomerServiceApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    docker compose down -v
    docker compose -f docker-compose.observability.yml down -v
    $MAVEN clean
    echo "Done. Run './run.sh all' to start from scratch."
    ;;

  stop)
    echo "Stopping everything..."
    pgrep -f 'CustomerServiceApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    docker compose down
    docker compose -f docker-compose.observability.yml down
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
    echo "║                   customer-service status                   ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""

    # Docker
    if docker info >/dev/null 2>&1; then
      echo "  Docker              ✅ running"
    else
      echo "  Docker              ❌ not running"
    fi

    # Spring Boot app
    if pgrep -f 'CustomerServiceApplication' >/dev/null 2>&1; then
      echo "  Spring Boot app     ✅ running (PID $(pgrep -f 'CustomerServiceApplication' | head -1))"
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
    for svc in pgadmin kafka-ui redisinsight; do
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
    for svc in customerservice-prometheus customerservice-grafana customerservice-lgtm customerservice-zipkin customerservice-pyroscope; do
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
    echo "  ── URLs ─────────────────────────────────────────────────────"
    echo "  App           http://localhost:8080"
    echo "  Swagger       http://localhost:8080/swagger-ui.html"
    echo "  pgAdmin       http://localhost:5050"
    echo "  Kafka UI      http://localhost:9080"
    echo "  RedisInsight  http://localhost:5540"
    echo "  Keycloak      http://localhost:9090"
    echo "  Grafana       http://localhost:3000"
    echo "  Grafana OTel  http://localhost:3001"
    echo "  Zipkin        http://localhost:9411"
    echo "  Pyroscope     http://localhost:4040"
    echo "  Prometheus    http://localhost:9090"
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
    echo "  app-profiled  start Spring app with Pyroscope profiling"
    echo "  all           start everything (infra + obs + app)"
    echo "  restart       stop + restart everything (keeps data)"
    echo "  simulate      run traffic simulation (default: 60 iterations, 2s pause)"
    echo "  stop          stop app + all containers"
    echo "  nuke          full cleanup — containers, volumes, build artifacts"
    echo "  status        check status of all services"
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
    echo "  clean         remove target/"
    echo "  install-tools install hadolint + lefthook via Homebrew"
    echo ""
    ;;
esac
