#!/usr/bin/env bash
# =============================================================================
# bin/pf-kind.sh — kubectl port-forward for the LOCAL kind cluster.
#
# Mirror of bin/pf-prod.sh but targets the kind cluster context
# (`kind-mirador-local`) and uses the +10000 offset so it can coexist with
# both compose (upstream ports) and the prod tunnel (+20000).
#
# Port map (kind / +10000):
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
#   Keycloak             19090
#   Unleash              14242
#   Unleash front-proxy  14243
#   Argo CD UI           18081   (port-forward to argocd-server:443, https)
#   Chaos Mesh dashboard 12333
#
# Usage:
#   bin/pf-kind.sh               # foreground, Ctrl-C to stop
#   bin/pf-kind.sh --daemon      # background, logs in /tmp/pf-kind-*.log
# =============================================================================

set -u

DAEMON=false
if [ "${1:-}" = "--daemon" ]; then DAEMON=true; fi

KIND_CONTEXT="${KIND_CONTEXT:-kind-mirador-local}"
PID_FILE="/tmp/pf-kind.pids"
LOG_DIR="/tmp/pf-kind-logs"
mkdir -p "$LOG_DIR"
rm -f "$PID_FILE"

# Sanity 1: the kind context exists.
if ! kubectl config get-contexts -o name | grep -qx "$KIND_CONTEXT"; then
  echo "❌  kubectl context '$KIND_CONTEXT' not found. Create it with:" >&2
  echo "    kind create cluster --name mirador-local --config deploy/kubernetes/kind-config.yaml" >&2
  exit 1
fi

# Sanity 2: the cluster responds. Switch once so nested kubectl calls below
# target the kind cluster without re-specifying --context every call.
kubectl config use-context "$KIND_CONTEXT" >/dev/null
if ! kubectl cluster-info >/dev/null 2>&1; then
  echo "❌  kind cluster '$KIND_CONTEXT' is not reachable (did Docker Desktop restart?)." >&2
  exit 1
fi

# Tunnels: kind uses upstream + 10000. Same service list as prod modulo any
# CRDs not deployed locally (Argo CD / Chaos Mesh are optional on kind).
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
  "keycloak      | infra     | svc/keycloak                  | 19090:8080"
  "unleash       | infra     | svc/unleash                   | 14242:4242"
  "unleash-proxy | infra     | svc/unleash-proxy             | 14243:3000"
  "argocd        | argocd    | svc/argocd-server             | 18081:443"
  "chaos-mesh    | chaos-mesh| svc/chaos-dashboard           | 12333:2333"
)

start_tunnel() {
  local name="$1" ns="$2" target="$3" ports="$4"
  local log="$LOG_DIR/$name.log"
  (
    while true; do
      echo "[$(date +%H:%M:%S)] kubectl port-forward -n $ns $target $ports" >> "$log"
      # `kubectl` is already pinned to the kind context via `use-context` above,
      # but pass --context explicitly so the tunnel cannot silently jump to the
      # GKE context if the user switches contexts externally while this runs.
      kubectl --context "$KIND_CONTEXT" port-forward -n "$ns" "$target" "$ports" >> "$log" 2>&1 || true
      echo "[$(date +%H:%M:%S)] tunnel dropped, reconnecting in 2 s" >> "$log"
      sleep 2
    done
  ) &
  local pid=$!
  echo "$pid $name" >> "$PID_FILE"
  printf "  %-12s → localhost:%s  (pid %d, log %s)\n" "$name" "${ports%%:*}" "$pid" "$log"
}

echo "🔗  Starting port-forward tunnels to $KIND_CONTEXT…"
for line in "${TUNNELS[@]}"; do
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

trap 'echo ""; echo "⏹   stopping kind tunnels…"; bin/pf-stop.sh >/dev/null 2>&1; exit 0' INT TERM
echo ""
echo "✅  ${#TUNNELS[@]} tunnels running. Ctrl-C to stop all."
wait
