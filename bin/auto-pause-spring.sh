#!/usr/bin/env bash
# =============================================================================
# bin/auto-pause-spring.sh — kill the local Spring app if idle for N min.
#
# Why this exists: when the laptop is the dev machine + the GitLab CI runner
# (ADR-0004), every Mac process competes for the same Docker Desktop VM
# memory (4 GB). A Spring Boot app left running in another terminal idles
# at ~600 MB and starves the build:production CI job, which then SIGKILLs.
# This script monitors http_server_requests_total via /actuator/metrics
# and SIGTERMs the Maven `spring-boot:run` process when the request count
# doesn't move for IDLE_MINUTES.
#
# Usage (run in a separate terminal alongside `./run.sh app`):
#   bin/auto-pause-spring.sh           # default: 10 min idle
#   IDLE_MINUTES=5 bin/auto-pause-spring.sh
#
# Restart manually: `./run.sh app` (the script doesn't relaunch).
# =============================================================================

set -uo pipefail

IDLE_MINUTES="${IDLE_MINUTES:-10}"
ACTUATOR_URL="${ACTUATOR_URL:-http://localhost:8080/actuator/metrics/http.server.requests}"
POLL_SECONDS=30

last_count=""
idle_polls=0
max_idle_polls=$(( (IDLE_MINUTES * 60) / POLL_SECONDS ))

echo "▸ auto-pause-spring: watching ${ACTUATOR_URL}"
echo "  Idle threshold: ${IDLE_MINUTES} min (${max_idle_polls} × ${POLL_SECONDS} s polls)"
echo "  Press Ctrl+C to stop the watcher (Spring keeps running)."

while true; do
  # Read total request count from actuator. Returns JSON like:
  #   {"name":"http.server.requests","measurements":[{"statistic":"COUNT","value":42.0},...]}
  count=$(curl -sSf -m 5 "$ACTUATOR_URL" 2>/dev/null \
    | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)
    for m in d.get("measurements",[]):
        if m.get("statistic")=="COUNT":
            print(int(m["value"])); break
except Exception:
    pass' 2>/dev/null)

  if [[ -z "$count" ]]; then
    echo "  $(date +%H:%M:%S) — actuator unreachable, Spring may be down already; exiting."
    exit 0
  fi

  if [[ -n "$last_count" && "$count" == "$last_count" ]]; then
    idle_polls=$(( idle_polls + 1 ))
    echo "  $(date +%H:%M:%S) — count=$count (idle ${idle_polls}/${max_idle_polls})"
    if [[ "$idle_polls" -ge "$max_idle_polls" ]]; then
      pid=$(pgrep -f "spring-boot:run" | head -1)
      if [[ -n "$pid" ]]; then
        echo "  ${IDLE_MINUTES} min idle — SIGTERM Maven spring-boot:run pid=$pid"
        kill -TERM "$pid"
      else
        echo "  ${IDLE_MINUTES} min idle — Spring process already gone, exiting"
      fi
      exit 0
    fi
  else
    if [[ -n "$last_count" ]]; then
      echo "  $(date +%H:%M:%S) — count=$count (Δ=$(( count - last_count ))) — idle reset"
    else
      echo "  $(date +%H:%M:%S) — count=$count (baseline)"
    fi
    idle_polls=0
  fi

  last_count="$count"
  sleep "$POLL_SECONDS"
done
