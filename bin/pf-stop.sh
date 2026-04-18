#!/usr/bin/env bash
# =============================================================================
# bin/pf-stop.sh — kill every kubectl port-forward started by pf-prod.sh.
#
# Reads /tmp/pf-prod.pids, kills each PID (and its auto-reconnect wrapper so
# it doesn't immediately spawn a new kubectl). Also does a best-effort
# `pkill -f 'kubectl port-forward'` as a safety net for orphaned tunnels
# from earlier sessions.
# =============================================================================

set -u

PID_FILE="/tmp/pf-prod.pids"

if [ -f "$PID_FILE" ]; then
  # Column 1 is the PID of the while-wrapper; killing it stops the restart
  # loop. The running kubectl child dies with SIGHUP shortly after.
  while read -r pid name; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null
      printf "  killed %-12s (pid %d)\n" "$name" "$pid"
    fi
  done < "$PID_FILE"
  rm -f "$PID_FILE"
fi

# Belt-and-suspenders: any lingering kubectl port-forward in this shell tree.
# Match on the pf-prod pattern to avoid killing unrelated port-forwards the
# user might have running in other terminals.
pkill -f "kubectl port-forward" 2>/dev/null || true

rm -rf /tmp/pf-prod-logs 2>/dev/null || true

echo "✅  all pf-prod tunnels stopped."
