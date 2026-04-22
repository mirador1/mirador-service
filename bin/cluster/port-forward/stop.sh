#!/usr/bin/env bash
# moved 2026-04-22 from bin/cluster/pf-stop.sh — per ~/.claude/CLAUDE.md subdirectory hygiene
# =============================================================================
# bin/cluster/port-forward/stop.sh — kill every kubectl port-forward started by prod.sh or kind.sh.
#
# Reads both /tmp/pf-prod.pids and /tmp/pf-kind.pids, kills each PID (and its
# auto-reconnect wrapper so it does not immediately spawn a new kubectl).
# Also does a best-effort `pkill -f 'kubectl port-forward'` as a safety net
# for orphaned tunnels.
# =============================================================================

set -u

for env in prod kind; do
  PID_FILE="/tmp/pf-$env.pids"
  [ -f "$PID_FILE" ] || continue
  # Column 1 is the PID of the while-wrapper; killing it stops the restart
  # loop. The running kubectl child dies with SIGHUP shortly after.
  while read -r pid name; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null
      printf "  killed %-12s (pid %d, env %s)\n" "$name" "$pid" "$env"
    fi
  done < "$PID_FILE"
  rm -f "$PID_FILE"
done

# Belt-and-suspenders: any lingering kubectl port-forward in this shell tree.
pkill -f "kubectl port-forward" 2>/dev/null || true
pkill -f "kubectl --context .* port-forward" 2>/dev/null || true

rm -rf /tmp/pf-prod-logs /tmp/pf-kind-logs 2>/dev/null || true

echo "✅  all pf tunnels stopped."
