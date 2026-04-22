#!/usr/bin/env bash
# =============================================================================
# bin/cluster/connect-prod.sh — refresh kubeconfig for the ephemeral GKE demo cluster
# and (optionally) open the desktop tools that read from it.
#
# Most desktop K8s / Docker tools (OpenLens, K9s, Headlamp, kubectl itself)
# just read ~/.kube/config — no port-forward needed for the admin plane.
# This script:
#   1. Runs `gcloud container clusters get-credentials` so the kubeconfig
#      points at the current ephemeral cluster.
#   2. Switches the current context so any subsequently opened tool lands
#      on the right cluster.
#   3. If OpenLens is installed, opens it.
#   4. Prints a short "now do port-forward for app services" reminder
#      (kubeconfig covers admin plane; pf-prod.sh covers app services).
# =============================================================================

set -u

PROJECT_ID="${TF_VAR_project_id:-project-8d6ea68c-33ac-412b-8aa}"
REGION="${TF_VAR_region:-europe-west1}"
CLUSTER_NAME="${TF_VAR_cluster_name:-mirador-prod}"

if ! command -v gcloud >/dev/null 2>&1; then
  echo "❌  gcloud not found. Install with: brew install --cask google-cloud-sdk"
  exit 1
fi

# 1. Refresh kubeconfig. gke-gcloud-auth-plugin is shipped with Google Cloud
#    SDK ≥ 410 — no extra install needed.
echo "🔑  refreshing kubeconfig for $CLUSTER_NAME in $REGION…"
if ! gcloud container clusters get-credentials "$CLUSTER_NAME" \
     --region "$REGION" --project "$PROJECT_ID" 2>/dev/null; then
  echo "❌  Cluster '$CLUSTER_NAME' not found. Run bin/cluster/demo/up.sh first."
  exit 1
fi

CONTEXT=$(kubectl config current-context)
echo "✅  current context: $CONTEXT"

# 2. Quick smoke test — count nodes.
NODES=$(kubectl get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')
echo "    nodes ready: ${NODES:-0}"

# 3. Open desktop tools that read ~/.kube/config.
if [ -d /Applications/OpenLens.app ]; then
  echo "🪟  opening OpenLens…"
  open -a OpenLens
elif [ -d /Applications/Lens.app ]; then
  echo "🪟  opening Lens…"
  open -a Lens
else
  echo "ℹ️   OpenLens not installed. Install: brew install --cask openlens"
fi

cat <<'EOF'

─────────────────────────────────────────────────────────────────────────
  Admin plane (pods / logs / exec / events)  → kubeconfig — ready now.
    • OpenLens / Lens (desktop)       ← reads ~/.kube/config
    • K9s  (brew install k9s)         ← same
    • Headlamp  (brew install --cask headlamp)
    • VS Code extension "Kubernetes"  ← same
    • IntelliJ Kubernetes plugin      ← same

  App plane (Grafana / backend / Unleash / Argo CD / Postgres / Kafka / …):
      bin/cluster/port-forward/prod.sh --daemon        # port-forward tunnels
      bin/cluster/port-forward/status.sh               # list + probe
      bin/cluster/port-forward/stop.sh                 # tear down
─────────────────────────────────────────────────────────────────────────
EOF
