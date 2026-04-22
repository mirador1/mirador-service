#!/usr/bin/env bash
# bin/run/install-tools.sh — implements `./run.sh install-tools`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo "Installing hadolint + lefthook via Homebrew..."
    brew install hadolint lefthook
    lefthook install
    echo ""
    echo "Tools installed. Git pre-push hook is now active."
    echo "Every 'git push' will automatically run './run.sh check' (unit tests)."
