#!/usr/bin/env bash
# bin/run/runner.sh — implements `./run.sh runner`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    ensure_docker
    echo "Starting GitLab Runner..."
    docker compose -f deploy/compose/runner.yml up -d
    echo ""
    echo "  Runner is up. Register it against gitlab.com with:"
    echo "    ./run.sh register-cloud <TOKEN>"
    echo "  Get the token: gitlab.com → Project → Settings → CI/CD → Runners → New project runner"
