#!/usr/bin/env bash
# =============================================================================
# simulate-traffic.sh — generates realistic API traffic for demo/observability
#
# Simulates a mix of client operations against the customer-service API:
#   - Login and JWT token refresh
#   - List customers (v1.0 and v2.0)
#   - Create, update, and delete customers
#   - View recent customers and aggregates
#   - Request bios and todos (external API calls → circuit breaker activity)
#
# Usage:
#   ./infra/simulate-traffic.sh              # default: 60 iterations, 2s pause
#   ./infra/simulate-traffic.sh 100 1        # 100 iterations, 1s pause
#   ./infra/simulate-traffic.sh 0            # infinite loop (Ctrl+C to stop)
#
# Prerequisites: curl, jq
# =============================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ITERATIONS="${1:-60}"
PAUSE="${2:-2}"
COUNTER=0

# --- Helpers -----------------------------------------------------------------

log()  { printf "\033[0;36m[%s] %s\033[0m\n" "$(date +%H:%M:%S)" "$1"; }
warn() { printf "\033[0;33m[%s] %s\033[0m\n" "$(date +%H:%M:%S)" "$1"; }

login() {
  TOKEN=$(curl -sf -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin"}' | jq -r '.token')
  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    warn "Login failed — retrying in 5s..."
    sleep 5
    login
  fi
}

api() {
  local method="$1" path="$2"
  shift 2
  curl -sf -X "$method" "$BASE_URL$path" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Api-Version: ${API_VERSION:-1.0}" \
    "$@" 2>/dev/null || true
}

random_name() {
  local first=("Emma" "Liam" "Sofia" "Noah" "Mia" "Lucas" "Amelia" "Hugo" "Luna" "Leo")
  local last=("Martin" "Bernard" "Dubois" "Thomas" "Robert" "Richard" "Petit" "Durand" "Leroy" "Moreau")
  echo "${first[$((RANDOM % ${#first[@]}))]}" "${last[$((RANDOM % ${#last[@]}))]}"
}

# --- Main loop ---------------------------------------------------------------

log "Starting traffic simulation against $BASE_URL"
log "Iterations: $([ "$ITERATIONS" -eq 0 ] && echo "infinite" || echo "$ITERATIONS") | Pause: ${PAUSE}s"

login
log "Authenticated — token acquired"

while [ "$ITERATIONS" -eq 0 ] || [ "$COUNTER" -lt "$ITERATIONS" ]; do
  COUNTER=$((COUNTER + 1))
  ACTION=$((RANDOM % 10))

  # Refresh token every 20 iterations
  if [ $((COUNTER % 20)) -eq 0 ]; then
    login
    log "Token refreshed"
  fi

  case $ACTION in
    0|1)
      # List customers v1
      log "#$COUNTER — GET /customers (v1.0)"
      API_VERSION=1.0 api GET "/customers?page=0&size=10" > /dev/null
      ;;
    2)
      # List customers v2
      log "#$COUNTER — GET /customers (v2.0)"
      API_VERSION=2.0 api GET "/customers?page=0&size=10" > /dev/null
      ;;
    3)
      # Create a customer
      NAME=$(random_name)
      FNAME=$(echo "$NAME" | awk '{print $1}')
      LNAME=$(echo "$NAME" | awk '{print $2}')
      EMAIL=$(echo "${FNAME,,}.${LNAME,,}.${RANDOM}@demo.com")
      log "#$COUNTER — POST /customers ($FNAME $LNAME)"
      api POST "/customers" -d "{\"name\":\"$FNAME $LNAME\",\"email\":\"$EMAIL\"}" > /dev/null
      ;;
    4)
      # Get recent customers
      log "#$COUNTER — GET /customers/recent"
      api GET "/customers/recent" > /dev/null
      ;;
    5)
      # Get aggregate
      log "#$COUNTER — GET /customers/aggregate"
      api GET "/customers/aggregate" > /dev/null
      ;;
    6)
      # Get summary
      log "#$COUNTER — GET /customers/summary"
      api GET "/customers/summary" > /dev/null
      ;;
    7)
      # Get bio for a random customer (triggers Ollama / circuit breaker)
      ID=$((RANDOM % 20 + 1))
      log "#$COUNTER — GET /customers/$ID/bio"
      api GET "/customers/$ID/bio" > /dev/null
      ;;
    8)
      # Get todos for a random customer (triggers JSONPlaceholder / resilience4j)
      ID=$((RANDOM % 20 + 1))
      log "#$COUNTER — GET /customers/$ID/todos"
      api GET "/customers/$ID/todos" > /dev/null
      ;;
    9)
      # Health check (unauthenticated)
      log "#$COUNTER — GET /actuator/health"
      curl -sf "$BASE_URL/actuator/health" > /dev/null || true
      ;;
  esac

  sleep "$PAUSE"
done

log "Simulation complete — $COUNTER iterations"
