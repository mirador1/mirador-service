#!/usr/bin/env bash
# =============================================================================
# bin/pf-prod.sh — one kubectl port-forward per service to reach the GKE demo.
#
# Per ADR-0025 the cluster has no public surface. The Angular UI runs on your
# laptop; this script spins up a local tunnel to every service the UI (and
# you) might need to talk to. Each tunnel uses a `1<default>` port so it
# coexists with the docker-compose stack on the standard ports.
#
# Port map:
#   Service              local:port
#   -------------------  -----------
#   Backend API          18080
#   Postgres             15432
#   Redis                16379
#   Kafka                19092
#   Grafana (LGTM)       13000
#   Tempo                13200
#   Loki                 13100
#   Mimir (Prom API)     19009
#   Pyroscope            14040
#   Keycloak             19091
#   Unleash              14242
#   Argo CD UI           18081   (port-forward to argocd-server:443, https)
#   Chaos Mesh dashboard 12333
#
# Usage:
#   bin/pf-prod.sh               # foreground, Ctrl-C to stop
#   bin/pf-prod.sh --daemon      # background, logs in /tmp/pf-prod-*.log
#
# Auto-restart: each tunnel runs inside an `until kubectl port-forward`
# loop so a pod kill (common under Chaos Mesh) is recovered in ~2 s without
# manual intervention.
# =============================================================================

set -u  # -e is NOT set; individual tunnels may fail while others continue

DAEMON=false
if [ "${1:-}" = "--daemon" ]; then DAEMON=true; fi

PID_FILE="/tmp/pf-prod.pids"
LOG_DIR="/tmp/pf-prod-logs"
mkdir -p "$LOG_DIR"
rm -f "$PID_FILE"

# Sanity: a cluster is reachable.
if ! kubectl cluster-info >/dev/null 2>&1; then
  echo "❌  kubectl cannot reach a cluster. Run bin/demo-up.sh first."
  exit 1
fi

# Tunnels: "name | namespace | svc | local:remote"
TUNNELS=(
  "backend       | app       | svc/mirador                   | 18080:8080"
  "postgres      | infra     | svc/postgresql                | 15432:5432"
  "redis         | infra     | svc/redis                     | 16379:6379"
  "kafka         | infra     | svc/kafka                     | 19092:9092"
  "grafana       | infra     | svc/lgtm                      | 13000:3000"
  "tempo         | infra     | svc/lgtm                      | 13200:3200"
  "loki          | infra     | svc/lgtm                      | 13100:3100"
  "mimir         | infra     | svc/lgtm                      | 19009:9009"
  "pyroscope     | infra     | svc/pyroscope                 | 14040:4040"
  "keycloak      | infra     | svc/keycloak                  | 19091:8080"
  "unleash       | infra     | svc/unleash                   | 14242:4242"
  "argocd        | argocd    | svc/argocd-server             | 18081:443"
  "chaos-mesh    | chaos-mesh| svc/chaos-dashboard           | 12333:2333"
)

start_tunnel() {
  local name="$1" ns="$2" target="$3" ports="$4"
  local log="$LOG_DIR/$name.log"
  # Endless loop so a pod restart (chaos-mesh pod-kill) reconnects automatically.
  # Each iteration reprints the target so logs are self-describing.
  (
    while true; do
      echo "[$(date +%H:%M:%S)] kubectl port-forward -n $ns $target $ports" >> "$log"
      kubectl port-forward -n "$ns" "$target" "$ports" >> "$log" 2>&1 || true
      echo "[$(date +%H:%M:%S)] tunnel dropped, reconnecting in 2 s" >> "$log"
      sleep 2
    done
  ) &
  local pid=$!
  echo "$pid $name" >> "$PID_FILE"
  printf "  %-12s → localhost:%s  (pid %d, log %s)\n" "$name" "${ports%%:*}" "$pid" "$log"
}

echo "🔗  Starting port-forward tunnels to $(kubectl config current-context)…"
for line in "${TUNNELS[@]}"; do
  # Split on pipe and trim whitespace per field.
  IFS='|' read -r n ns tgt prt <<< "$line"
  n=$(echo "$n" | xargs); ns=$(echo "$ns" | xargs)
  tgt=$(echo "$tgt" | xargs); prt=$(echo "$prt" | xargs)
  start_tunnel "$n" "$ns" "$tgt" "$prt"
done

if $DAEMON; then
  echo ""
  echo "✅  ${#TUNNELS[@]} tunnels running in background. PIDs in $PID_FILE."
  echo "   bin/pf-status.sh   list + test connectivity"
  echo "   bin/pf-stop.sh     tear everything down"
  exit 0
fi

# Foreground: wait forever; Ctrl-C triggers trap which kills all children.
trap 'echo ""; echo "⏹   stopping tunnels…"; bin/pf-stop.sh >/dev/null 2>&1; exit 0' INT TERM
echo ""
echo "✅  ${#TUNNELS[@]} tunnels running. Ctrl-C to stop all."
wait
