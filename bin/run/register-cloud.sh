#!/usr/bin/env bash
# bin/run/register-cloud.sh — implements `./run.sh register-cloud`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    TOKEN="${2:-}"
    if [ -z "$TOKEN" ]; then
      echo "Usage: ./run.sh register-cloud <TOKEN>"
      echo ""
      echo "  Get the token from: gitlab.com → Project → Settings → CI/CD → Runners"
      exit 1
    fi
    ./scripts/register-runner.sh cloud "$TOKEN"
