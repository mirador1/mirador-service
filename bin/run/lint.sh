#!/usr/bin/env bash
# bin/run/lint.sh — implements `./run.sh lint`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo "Running Dockerfile linting..."
    if command -v hadolint &>/dev/null; then
      hadolint Dockerfile
    else
      echo "hadolint not found — running via Docker (install with: ./run.sh install-tools)"
      docker run --rm -i hadolint/hadolint < Dockerfile
    fi
