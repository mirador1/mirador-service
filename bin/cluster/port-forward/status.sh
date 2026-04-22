#!/usr/bin/env bash
# moved 2026-04-22 from bin/cluster/pf-status.sh — per ~/.claude/CLAUDE.md subdirectory hygiene
# =============================================================================
# bin/cluster/port-forward/status.sh — list active kind + prod tunnels and probe each.
#
# Three-environment policy (docs/architecture/environments-and-flows.md):
#   Local compose = upstream ports
#   Kind          = upstream + 10000   (bin/cluster/port-forward/kind.sh)
#   Prod          = upstream + 20000   (bin/cluster/port-forward/prod.sh)
# Compose is not listed here — those are regular docker containers, checked
# with `docker compose ps`.
# =============================================================================

set -u

declare -A KIND_PORT=(
  [backend]=18080 [postgres]=15432 [redis]=16379 [kafka]=19092
  [grafana]=13000 [tempo]=13200    [loki]=13100  [mimir]=19009
  [pyroscope]=14040 [keycloak]=19090
  [unleash]=14242 [unleash-proxy]=14243
  [argocd]=18081 [chaos-mesh]=12333
)

declare -A PROD_PORT=(
  [backend]=28080 [postgres]=25432 [redis]=26379 [kafka]=29092
  [grafana]=23000 [tempo]=23200    [loki]=23100  [mimir]=29009
  [pyroscope]=24040 [keycloak]=29090
  [unleash]=24242 [unleash-proxy]=24243
  [argocd]=28081 [chaos-mesh]=22333
)

declare -A HEALTH_PATH=(
  [backend]=/actuator/health  [grafana]=/api/health
  [unleash]=/health           [keycloak]=/health/ready
  [argocd]=/healthz
)

probe_env() {
  local env="$1"; shift
  local -n MAP=$1
  local pid_file="/tmp/pf-$env.pids"
  printf "\n── %s (pids: %s) ──\n" "$env" "$([ -f "$pid_file" ] && echo "$pid_file" || echo "not started")"
  printf "%-12s %-6s %-7s %-10s %s\n" "NAME" "PID" "PORT" "TCP" "HTTP"
  for name in "${!MAP[@]}"; do
    local port="${MAP[$name]}"
    local pid="-"
    if [ -f "$pid_file" ]; then
      pid=$(grep " $name\$" "$pid_file" 2>/dev/null | awk '{print $1}')
      pid=${pid:-"-"}
    fi
    # TCP probe (macOS -G, Linux -w); either option is accepted.
    local tcp="closed"
    if nc -z -G 1 localhost "$port" 2>/dev/null || nc -z -w 1 localhost "$port" 2>/dev/null; then
      tcp="open"
    fi
    local path="${HEALTH_PATH[$name]:-}"
    local http="-"
    if [ -n "$path" ] && [ "$tcp" = "open" ]; then
      http=$(curl -s -k -o /dev/null -w "%{http_code}" --max-time 2 \
        "http://localhost:$port$path" 2>/dev/null || echo "err")
    fi
    printf "%-12s %-6s %-7s %-10s %s\n" "$name" "$pid" "$port" "$tcp" "$http"
  done
}

probe_env kind KIND_PORT
probe_env prod PROD_PORT
