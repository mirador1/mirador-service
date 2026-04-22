#!/usr/bin/env bash
# bin/run/restart.sh — implements `./run.sh restart`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    ensure_docker
    echo "Restarting everything (clean)..."
    # Kill the running Spring app (target Java process only, not Docker)
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    # Stop all containers (both compose files)
    docker compose -f deploy/compose/observability.yml down
    docker compose down
    # Start infra (not the app container — we run locally via Maven).
    # Explicit `--profile` flags needed after the 2026-04 profile
    # taxonomy refactor: ollama/keycloak live under `full`;
    # cloudbeaver/kafka-ui/redisinsight under `admin`.
    docker compose --profile full --profile admin up -d \
      db kafka redis ollama keycloak cloudbeaver kafka-ui redisinsight
    # Start observability stack (profile-gated since 2026-04-20).
    docker compose -f deploy/compose/observability.yml --profile observability up -d
    # Wait for DB to be healthy before starting the app
    echo -n "Waiting for PostgreSQL"
    until docker inspect -f '{{.State.Health.Status}}' postgres-demo 2>/dev/null | grep -q healthy; do
      echo -n "."
      sleep 2
    done
    echo " ready!"
    echo "Infrastructure ready. Starting app..."
    $MVNW spring-boot:run
