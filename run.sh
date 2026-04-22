#!/usr/bin/env bash
# =============================================================================
# run.sh — thin dispatcher — routes `./run.sh <case>` to `bin/run/<case>.sh`.
#
# Phase B-7-8 (2026-04-22): each case was moved to its own file under
# bin/run/ ("1 sub-script per case" per user request). The original 763-LOC
# monolith becomes: this dispatcher + bin/run/_preamble.sh (shared helpers)
# + one file per case (see `ls bin/run/*.sh`).
#
# Usage:
#   ./run.sh <case>       # e.g. ./run.sh db, ./run.sh all, ./run.sh nuke
#   ./run.sh              # lists available cases
#
# To add a new case: create bin/run/<new-case>.sh and make it executable.
# No change needed here — the dispatcher discovers scripts dynamically.
# =============================================================================

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
RUN_DIR="$REPO_ROOT/bin/run"

# Without arguments, list available cases (sorted, cleaner than `ls -1`).
if [ $# -eq 0 ]; then
  echo "Usage: ./run.sh <case>"
  echo ""
  echo "Available cases:"
  for f in "$RUN_DIR"/*.sh; do
    bn="$(basename "$f" .sh)"
    [ "$bn" != "_preamble" ] && echo "  $bn"
  done | sort
  echo ""
  echo "Each case is implemented in bin/run/<case>.sh. Shared helpers live in"
  echo "bin/run/_preamble.sh (sourced by every sub-script)."
  exit 0
fi

# Lookup and exec the case script
CASE="$1"
shift
SCRIPT="$RUN_DIR/$CASE.sh"
if [ ! -x "$SCRIPT" ]; then
  echo "Unknown case: $CASE"
  echo "Run './run.sh' with no arguments to list available cases."
  exit 1
fi
exec "$SCRIPT" "$@"
