#!/usr/bin/env bash
# moved 2026-04-22 from bin/cluster/openlens-refresh.sh — per ~/.claude/CLAUDE.md subdirectory hygiene
# =============================================================================
# bin/cluster/openlens/refresh.sh — refresh ~/.kube/config + restart OpenLens.
#
# OpenLens reads `~/.kube/config` at each "Connect" click. It already has
# both Mirador clusters registered in its local store (see
# ~/Library/Application Support/OpenLens/lens-cluster-store.json), but:
#
#  • kind cluster — if `kind export kubeconfig --internal` was last run
#    (CI does this per ADR-0028), the server URL points at a Docker-
#    bridge hostname that doesn't resolve from the Mac. We reset it
#    to the default public `127.0.0.1:<randomPort>` mode.
#  • GKE cluster — the Autopilot cluster is ephemeral (ADR-0022,
#    ~€2/month vs €190/month 24/7). This script checks whether it's
#    up, and if so, refreshes the access token via
#    `gcloud container clusters get-credentials`. If down, we leave the
#    stale kubeconfig entry — OpenLens will show the cluster grayed out
#    instead of erroring out on startup.
#
# OpenLens is restarted after the refresh so its in-memory view of the
# kubeconfig matches what's on disk. Without restart the app sometimes
# caches the previous token and fails its first API call.
#
# Usage:
#   bin/cluster/openlens/refresh.sh            # refresh + restart OpenLens
#   bin/cluster/openlens/refresh.sh --no-open  # refresh only, don't touch the app
# =============================================================================

set -u  # -e is NOT set; GKE-down is expected, kind-down is recoverable.

KIND_CLUSTER="${KIND_CLUSTER:-mirador-local}"
# GKE coordinates. Sourced from gcloud config so this script is
# portable across Mirador workstations.
GKE_PROJECT="${GKE_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
GKE_CLUSTER="${GKE_CLUSTER:-mirador-prod}"
GKE_REGION="${GKE_REGION:-europe-west1}"

OPEN_LENS=1
for arg in "$@"; do
  [[ "$arg" == "--no-open" ]] && OPEN_LENS=0
done

echo "🔭  OpenLens kubeconfig refresh — $(date +%H:%M:%S)"
echo

# ── 1. kind cluster ───────────────────────────────────────────────────────
echo "📦  kind cluster"
if kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER"; then
  # Re-export in public mode. Idempotent — if kubeconfig was already
  # public this is a no-op that just rewrites the same bytes.
  if kind export kubeconfig --name "$KIND_CLUSTER" >/dev/null 2>&1; then
    server=$(kubectl config view --context="kind-${KIND_CLUSTER}" \
                                 --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null)
    echo "   ✅ context 'kind-${KIND_CLUSTER}' → ${server}"
  else
    echo "   ⚠️  kind export failed — cluster may be partially torn down."
  fi
else
  echo "   ⏸  not running — start with: ./run.sh k8s-local"
fi
echo

# ── 2. GKE cluster ────────────────────────────────────────────────────────
echo "☁️   GKE cluster"
if [[ -z "$GKE_PROJECT" ]]; then
  echo "   ⚠️  no gcloud project set — skip. Run: gcloud config set project <id>"
elif ! command -v gcloud >/dev/null 2>&1; then
  echo "   ⚠️  gcloud CLI missing — install Google Cloud SDK."
else
  # Short-circuit on missing cluster — avoids the 60 s hang when the
  # ephemeral cluster is torn down.
  existing=$(gcloud container clusters list \
              --project "$GKE_PROJECT" \
              --filter="name=$GKE_CLUSTER AND location=$GKE_REGION" \
              --format="value(name)" 2>/dev/null)
  if [[ -n "$existing" ]]; then
    if gcloud container clusters get-credentials "$GKE_CLUSTER" \
        --region "$GKE_REGION" \
        --project "$GKE_PROJECT" >/dev/null 2>&1; then
      # gcloud rewrites the context name. Show what we got.
      ctx=$(kubectl config current-context 2>/dev/null)
      echo "   ✅ context '${ctx}' refreshed"
    else
      echo "   ⚠️  get-credentials failed — check gcloud auth."
    fi
  else
    echo "   ⏸  cluster not provisioned (ADR-0022 ephemeral pattern)."
    echo "      Bring it up with: ./bin/demo-up.sh"
  fi
fi
echo

# ── 3. OpenLens store sanity check ────────────────────────────────────────
STORE="$HOME/Library/Application Support/OpenLens/lens-cluster-store.json"
if [[ -f "$STORE" ]]; then
  count=$(python3 -c "import json; d=json.load(open('$STORE')); print(len(d.get('clusters',[])))" 2>/dev/null || echo "?")
  echo "🗂   lens-cluster-store.json → ${count} cluster(s) registered"
else
  echo "🗂   lens-cluster-store.json missing — launch OpenLens once, then re-run."
fi
echo

# ── 4. Restart OpenLens ───────────────────────────────────────────────────
if [[ "$OPEN_LENS" == "1" ]]; then
  if pgrep -x OpenLens >/dev/null 2>&1; then
    echo "🔄  restarting OpenLens…"
    osascript -e 'tell application "OpenLens" to quit' 2>/dev/null
    sleep 2
    open -a OpenLens 2>/dev/null && echo "   ✅ reopened" || echo "   ⚠️  couldn't reopen — launch manually."
  else
    echo "🚀  OpenLens not running — launching…"
    open -a OpenLens 2>/dev/null && echo "   ✅ launched" || echo "   ⚠️  OpenLens.app not found. Install from https://github.com/MuhammedKalkan/OpenLens"
  fi
fi

echo
echo "Done. OpenLens should show both clusters in the catalog (⌘K)."
echo "Cluster grayed out = endpoint unreachable (GKE down or kind not started)."
