#!/usr/bin/env bash
# bin/run/simulate.sh — implements `./run.sh simulate`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo "Starting traffic simulation..."
    ./scripts/simulate-traffic.sh "${2:-60}" "${3:-2}"
