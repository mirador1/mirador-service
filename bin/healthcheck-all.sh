#!/usr/bin/env bash
# =============================================================================
# bin/healthcheck-all.sh — one-glance status of every Mirador local service.
#
# Why this script exists: the project spawns ~12 containers + 2 host
# processes (Spring app + Angular dev server). Diagnosing "why is the
# UI showing 'backend down'?" used to mean running `docker ps` then
# `docker inspect` then `curl /actuator/health` then `lsof -i :4200`.
# This script does all of it in one pass, with a clear status table
# so you find the broken thing in 5 seconds.
#
# Usage:
#   bin/healthcheck-all.sh           # human-readable table (default)
#   bin/healthcheck-all.sh --json    # machine-readable for scripts
#   bin/healthcheck-all.sh --watch   # re-run every 3 s
#
# Exit code: 0 if everything is UP, 1 if any required service is DOWN
# (so it can be wired into pre-merge / pre-demo checks).
# =============================================================================

set -uo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'
BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'

MODE="${1:-human}"

# Each entry: <label>|<probe-command>|<expected-substring>|<required>
# - probe-command: stdout match against expected-substring → UP
# - required=1 → DOWN counts toward exit code 1
SERVICES=(
  "Postgres (mirador app db)|docker exec postgres-demo pg_isready -U demo|accepting connections|1"
  "Kafka (mirador events)|docker exec kafka-demo kafka-topics.sh --bootstrap-server localhost:9092 --list|.|1"
  "Redis (cache + idempotency)|docker exec redis-demo redis-cli ping|PONG|1"
  "Spring Boot backend (:8080)|curl -sSf -m 3 http://localhost:8080/actuator/health|UP|1"
  "Angular UI (:4200)|curl -sSf -m 3 http://localhost:4200|<!doctype html|0"
  "Grafana / LGTM (:3000)|curl -sSf -m 3 http://localhost:3000/api/health|ok|0"
  "Tempo (:3200)|curl -sSf -m 3 http://localhost:3200/ready|ready|0"
  "Mimir / Prom (:9091)|curl -sSf -m 3 http://localhost:9091/api/v1/status/buildinfo|version|0"
  "Kafka UI (:9080)|curl -sSf -m 3 http://localhost:9080|.|0"
  "Redis Commander (:8082)|curl -sSf -m 3 http://localhost:8082|.|0"
  "Swagger UI (:8080/swagger-ui)|curl -sSf -m 3 http://localhost:8080/swagger-ui/index.html|swagger|0"
  "kind cluster (control plane)|docker ps --filter name=mirador-local-control-plane --format {{.Status}}|Up|0"
)

probe() {
  local cmd="$1" expected="$2"
  if out=$(eval "$cmd" 2>&1) && echo "$out" | grep -qiF "$expected"; then
    return 0
  fi
  return 1
}

required_down=0

if [[ "$MODE" == "--json" ]]; then
  printf '['
  first=1
  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r label cmd expected required <<< "$entry"
    status="UP"; probe "$cmd" "$expected" || status="DOWN"
    [[ $first -eq 0 ]] && printf ','
    first=0
    printf '{"label":"%s","status":"%s","required":%s}' "$label" "$status" "$required"
    [[ "$status" == "DOWN" && "$required" == "1" ]] && required_down=1
  done
  printf ']\n'
  exit "$required_down"
fi

if [[ "$MODE" == "--watch" ]]; then
  while true; do
    clear
    "$0"
    echo -e "\n${DIM}refresh every 3 s — Ctrl+C to exit${NC}"
    sleep 3
  done
fi

# Human-readable table
echo -e "${BOLD}Mirador local stack health $(date +%H:%M:%S)${NC}"
echo "  ─────────────────────────────────────────────────────────"
for entry in "${SERVICES[@]}"; do
  IFS='|' read -r label cmd expected required <<< "$entry"
  if probe "$cmd" "$expected"; then
    printf "  ${GREEN}✓${NC} %-44s ${DIM}UP${NC}\n" "$label"
  elif [[ "$required" == "1" ]]; then
    printf "  ${RED}✗${NC} %-44s ${RED}DOWN ${DIM}(required)${NC}\n" "$label"
    required_down=1
  else
    printf "  ${YELLOW}!${NC} %-44s ${YELLOW}DOWN ${DIM}(optional)${NC}\n" "$label"
  fi
done
echo "  ─────────────────────────────────────────────────────────"

if [[ $required_down -eq 0 ]]; then
  echo -e "  ${GREEN}${BOLD}OK${NC} — all required services up."
else
  echo -e "  ${RED}${BOLD}NOT READY${NC} — fix required services before running the demo."
fi

exit "$required_down"
