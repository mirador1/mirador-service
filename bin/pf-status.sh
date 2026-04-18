#!/usr/bin/env bash
# =============================================================================
# bin/pf-status.sh — list the tunnels started by pf-prod.sh and probe each.
#
# For every local port, print:
#   • the underlying kubectl PID (from /tmp/pf-prod.pids)
#   • a quick TCP probe (nc -z) to show whether the port is really open
#   • an HTTP probe to /actuator/health (or /health for Unleash) to confirm
#     the service behind the tunnel is responsive
# =============================================================================

set -u

PID_FILE="/tmp/pf-prod.pids"

declare -A SERVICE_PORT=(
  [backend]=18080
  [postgres]=15432
  [redis]=16379
  [kafka]=19092
  [grafana]=13000
  [tempo]=13200
  [loki]=13100
  [mimir]=19009
  [pyroscope]=14040
  [keycloak]=19091
  [unleash]=14242
  [argocd]=18081
  [chaos-mesh]=12333
)

declare -A HEALTH_PATH=(
  [backend]=/actuator/health
  [grafana]=/api/health
  [unleash]=/health
  [keycloak]=/health/ready
  [argocd]=/healthz
)

printf "%-12s %-6s %-7s %-10s %s\n" "NAME" "PID" "PORT" "TCP" "HTTP"
for name in "${!SERVICE_PORT[@]}"; do
  port="${SERVICE_PORT[$name]}"
  pid=$(grep " $name\$" "$PID_FILE" 2>/dev/null | awk '{print $1}')
  pid=${pid:-"-"}
  # TCP probe: nc -z returns 0 if port is open.
  if nc -z -G 1 localhost "$port" 2>/dev/null; then tcp="open"; else tcp="closed"; fi
  # HTTP probe where applicable — otherwise print "-".
  path="${HEALTH_PATH[$name]:-}"
  if [ -n "$path" ] && [ "$tcp" = "open" ]; then
    # --insecure for argocd self-signed cert.
    http=$(curl -s -k -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:$port$path" 2>/dev/null || echo "err")
  else
    http="-"
  fi
  printf "%-12s %-6s %-7s %-10s %s\n" "$name" "$pid" "$port" "$tcp" "$http"
done
