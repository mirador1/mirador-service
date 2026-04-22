#!/usr/bin/env bash
# bin/run/nuke.sh — implements `./run.sh nuke`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    ensure_docker
    echo "Full cleanup — removing containers, volumes, and build artifacts..."
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    docker compose down -v
    docker compose -f deploy/compose/observability.yml down -v
    docker compose -f deploy/compose/runner.yml down -v 2>/dev/null || true
    $MAVEN clean
    echo "Done. Run './run.sh all' to start from scratch."
