#!/usr/bin/env bash
# bin/run/test.sh — implements `./run.sh test`.
# Aliases: check — dispatched to this same script via the symlinks created alongside.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo "Running unit tests (fast, no Docker)..."
    $MAVEN test
