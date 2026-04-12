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
IMAGE="spring-api:local"

case "$1" in

  # ---------------------------------------------------------------------------
  # Infrastructure
  # ---------------------------------------------------------------------------

  db)
    echo "Starting PostgreSQL..."
    docker compose up -d db
    ;;

  kafka)
    echo "Starting Kafka..."
    docker compose up -d kafka
    ;;

  obs)
    echo "Starting observability stack..."
    docker compose -f docker-compose.observability.yml up -d
    ;;

  app)
    echo "Starting Spring app (local)..."
    $MVNW spring-boot:run
    ;;

  all)
    echo "Starting everything..."
    docker compose up -d db kafka
    docker compose -f docker-compose.observability.yml up -d
    $MVNW spring-boot:run
    ;;

  stop)
    echo "Stopping all containers..."
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

  *)
    echo ""
    echo "Usage: ./run.sh <command>"
    echo ""
    echo "Infrastructure:"
    echo "  db            start PostgreSQL"
    echo "  kafka         start Kafka"
    echo "  obs           start observability stack"
    echo "  app           start Spring app (local)"
    echo "  all           start everything"
    echo "  stop          stop all containers"
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
    echo "  clean         remove target/"
    echo "  install-tools install hadolint + lefthook via Homebrew"
    echo ""
    ;;
esac
