#!/usr/bin/env bash
# moved 2026-04-22 from bin/cluster/pgweb-kind-up.sh — per ~/.claude/CLAUDE.md subdirectory hygiene
# =============================================================================
# bin/cluster/pgweb/kind-up.sh — start the "Kind" pgweb container (port 8082).
#
# Mirror of bin/cluster/pgweb/prod-up.sh targeting the KIND cluster instead of GKE.
# Kind uses the +10000 port offset, so the Postgres tunnel is on localhost:15432
# (see docs/architecture/environments-and-flows.md).
#
# Pre-requisites:
#   1. kind cluster up (`kind create cluster --name mirador-local …`)
#   2. bin/cluster/port-forward/kind.sh --daemon           — tunnels open (we need 15432 here)
#
# Usage:
#   bin/cluster/pgweb/kind-up.sh            # start pgweb-kind on localhost:8082
#   bin/cluster/pgweb/kind-up.sh --down      # stop the container
# =============================================================================

set -eu

KIND_CONTEXT="${KIND_CONTEXT:-kind-mirador-local}"

if [ "${1:-}" = "--down" ]; then
  docker compose --profile kind-tunnel stop pgweb-kind 2>/dev/null || true
  docker compose --profile kind-tunnel rm -f pgweb-kind 2>/dev/null || true
  echo "🛑  pgweb-kind stopped."
  exit 0
fi

# Sanity 1: the kind context exists + is reachable.
if ! kubectl config get-contexts -o name | grep -qx "$KIND_CONTEXT"; then
  echo "❌  kubectl context '$KIND_CONTEXT' not found." >&2
  exit 1
fi

# Sanity 2: the Postgres tunnel is open on the host (kind = +10000).
if ! { nc -z -G 1 localhost 15432 2>/dev/null || nc -z -w 1 localhost 15432 2>/dev/null; }; then
  echo "❌  localhost:15432 is not open. Run bin/cluster/port-forward/kind.sh --daemon first." >&2
  exit 1
fi

# Fetch the DB password from the kind cluster Secret (same Secret name as prod
# — ESO is not present on kind, so the Secret is created by the local overlay's
# Secret generator or by a one-off kubectl create, whichever is current).
PGWEB_DB_PASSWORD=$(kubectl --context "$KIND_CONTEXT" get secret mirador-secrets -n app \
    -o jsonpath='{.data.DB_PASSWORD}' 2>/dev/null | base64 -d || true)

# Fallback to the compose default (demo/demo) if the Secret is not present —
# kind is a dev cluster, not a secrets-managed environment, so this is fine.
PGWEB_DB_PASSWORD="${PGWEB_DB_PASSWORD:-demo}"
export PGWEB_DB_PASSWORD

echo "🔑  Using DB password ${PGWEB_DB_PASSWORD:0:1}… (first char only shown)."
echo "🐘  Starting pgweb-kind on http://localhost:8082 → kind Postgres via tunnel…"

docker compose --profile kind-tunnel up -d pgweb-kind

for _ in $(seq 1 15); do
  if nc -z localhost 8082 2>/dev/null; then
    echo "✅  pgweb-kind ready at http://localhost:8082"
    echo "    The Angular UI picks this up automatically on 'Kind' environment."
    exit 0
  fi
  sleep 1
done

echo "⚠️   pgweb-kind started but port 8082 is not responding after 15s."
echo "    Check logs: docker compose logs pgweb-kind"
exit 1
