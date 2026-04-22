#!/usr/bin/env bash
# bin/run/runner-stop.sh — implements `./run.sh runner-stop`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo "Stopping GitLab Runner..."
    docker compose -f deploy/compose/runner.yml down
