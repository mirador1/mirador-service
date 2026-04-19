#!/usr/bin/env bash
# =============================================================================
# bin/nightly-smoke.sh — once-per-day local smoke test (#8 from proposal list).
#
# Why this exists: the local Docker Compose stack drifts silently when
# upstream images bump their baseline (Postgres 17.x → 17.y, Kafka
# config keys deprecated, Grafana plugins disappear from the catalog,
# …). The first time you find out is when YOU try to run the demo
# and something is broken at the worst possible moment. This script
# brings the stack up + runs healthchecks + tears down — designed
# to run via `launchctl` (macOS) or `cron` once a night.
#
# What it does:
#   1. ./run.sh all             — bring up everything (with --profile full)
#   2. wait up to 3 min for Spring health to flip to UP
#   3. bin/healthcheck-all.sh   — verify every container probe
#   4. bin/audit-lighthouse.sh  — capture today's Lighthouse score
#      (UI repo, optional, only if mirador-ui sibling exists)
#   5. ./run.sh stop            — leave the laptop clean
#
# Output: appends a one-line summary to docs/audit/nightly-smoke.log
# (timestamp + PASS/FAIL + duration). 90 days of history is enough to
# spot a slow-drift trend ("Lighthouse perf dropped 12 pts in 3 weeks").
#
# Schedule (macOS launchd, runs at 03:00 local):
#   cp bin/launchd/com.mirador.nightly-smoke.plist ~/Library/LaunchAgents/
#   launchctl load ~/Library/LaunchAgents/com.mirador.nightly-smoke.plist
# (the plist file is shipped under bin/launchd/ for reference)
# =============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

LOG_FILE="docs/audit/nightly-smoke.log"
mkdir -p "$(dirname "$LOG_FILE")"

start_ts=$(date +%s)
status="PASS"
notes=""

log() { echo "$(date +%H:%M:%S) $*"; }

trap 'echo "[$(date "+%Y-%m-%d %H:%M:%S")] interrupted (status=ABORT)" >> "$LOG_FILE"; ./run.sh stop 2>/dev/null; exit 130' INT TERM

log "▸ bringing stack up via ./run.sh all (--profile full)…"
if ! ./run.sh all > /tmp/nightly-smoke-up.log 2>&1; then
  status="FAIL"
  notes="run.sh all failed (see /tmp/nightly-smoke-up.log)"
fi

if [[ "$status" == "PASS" ]]; then
  log "▸ waiting up to 3 min for Spring /actuator/health → UP…"
  for i in $(seq 1 36); do
    if curl -sSf -m 3 http://localhost:8080/actuator/health >/dev/null 2>&1; then
      log "  Spring up after ${i}×5s"; break
    fi
    sleep 5
  done
  if ! curl -sSf -m 3 http://localhost:8080/actuator/health >/dev/null 2>&1; then
    status="FAIL"; notes="Spring never reached UP within 3 min"
  fi
fi

if [[ "$status" == "PASS" ]]; then
  log "▸ healthcheck-all…"
  if ! ./bin/healthcheck-all.sh > /tmp/nightly-smoke-health.log 2>&1; then
    status="FAIL"; notes="healthcheck-all reported a required service down"
  fi
fi

# Optional: run Lighthouse if the UI sibling repo exists + npm script available.
if [[ -d "../../js/mirador-ui" && "$status" == "PASS" ]]; then
  log "▸ Lighthouse audit (UI :4200) — optional, skipping if UI not up…"
  if curl -sSf -m 3 http://localhost:4200 >/dev/null 2>&1; then
    (cd ../../js/mirador-ui && bin/audit-lighthouse.sh > /tmp/nightly-smoke-lh.log 2>&1) || \
      notes="${notes}; lighthouse failed (non-blocking)"
  else
    notes="${notes}; UI :4200 not up, lighthouse skipped"
  fi
fi

log "▸ tearing down…"
./run.sh stop > /tmp/nightly-smoke-down.log 2>&1 || true

duration=$(( $(date +%s) - start_ts ))
echo "[$(date "+%Y-%m-%d %H:%M:%S")] status=$status duration=${duration}s${notes:+ notes=$notes}" \
  | tee -a "$LOG_FILE"

[[ "$status" == "PASS" ]] && exit 0 || exit 1
