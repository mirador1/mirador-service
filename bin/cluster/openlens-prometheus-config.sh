#!/usr/bin/env bash
# =============================================================================
# bin/cluster/openlens-prometheus-config.sh
#
# Wires OpenLens's "Metrics" feature to whichever Prometheus is currently
# deployed in the kind-mirador-local cluster:
#   - If the `local-prom` overlay is applied (kube-prometheus-stack
#     present) → point at `monitoring/prometheus-stack-kube-prom-prometheus:9090`.
#     This is the native source the OpenLens "Auto" provider also finds;
#     the explicit config we write here just removes ambiguity and avoids
#     the auto-detect false-negative when the metric naming is ambiguous.
#   - Else (default `local` overlay) → point at lgtm's Mimir at
#     `infra/lgtm:9009`. ADR-0038 explains why this works (kubeletstats
#     + k8s_cluster + metricstransform aliases).
#
# Why this script exists:
# OpenLens stores per-cluster Prometheus config in its local state file
# (lens-cluster-store.json) under ~/Library/Application Support/OpenLens/.
# That file is host-local and gitignored. This script writes the known-good
# config so the setup is reproducible across machines without clicking
# through OpenLens → Settings → Metrics → Prometheus service.
#
# Detection rule:
# `kubectl get svc -n monitoring prometheus-stack-kube-prom-prometheus`.
# If the get succeeds (exit 0), use kube-prom; else fall back to lgtm.
#
# Usage:
#   bin/cluster/openlens-prometheus-config.sh                  # auto-detect
#   bin/cluster/openlens-prometheus-config.sh --force-lgtm     # always lgtm
#   bin/cluster/openlens-prometheus-config.sh --force-kubeprom # always kube-prom
#   bin/cluster/openlens-prometheus-config.sh kind-other       # other context
#
# Prerequisite:
#   - OpenLens is installed and has been opened at least once (so the
#     lens-cluster-store.json exists).
#   - The kind-mirador-local cluster has been imported into OpenLens
#     (usually automatic when kubeconfig has the context).
#
# Companion fix: deploy/kubernetes/base/observability/lgtm.yaml sets the
# mimir service's targetPort to 9090 (was 9009). Without that fix the
# service routes to a closed port in the pod and OpenLens gets
# ECONNREFUSED regardless of this config.
# =============================================================================

set -euo pipefail

STORE="$HOME/Library/Application Support/OpenLens/lens-cluster-store.json"

# ── Parse args ──────────────────────────────────────────────────────────────
FORCE_MODE=""
CONTEXT="kind-mirador-local"
for arg in "$@"; do
  case "$arg" in
    --force-lgtm)     FORCE_MODE="lgtm" ;;
    --force-kubeprom) FORCE_MODE="kubeprom" ;;
    --help|-h)
      sed -n '2,40p' "$0"   # print the usage block
      exit 0
      ;;
    *)
      CONTEXT="$arg"
      ;;
  esac
done

if [[ ! -f "$STORE" ]]; then
  echo "✗ OpenLens store not found at $STORE"
  echo "  Open OpenLens once, then re-run."
  exit 1
fi

# ── Detect which Prometheus is deployed ─────────────────────────────────────
# kube-prom-stack is preferred when present because it's the canonical
# OpenLens-supported source (no Mimir/OTel dance), and OpenLens's "Auto"
# provider also detects it natively.
NS=""; SVC=""; PORT=""; PROVIDER=""; LABEL=""

if [[ "$FORCE_MODE" == "lgtm" ]]; then
  NS="infra"; SVC="lgtm"; PORT=9009; PROVIDER="lens"; LABEL="lgtm Mimir (forced)"
elif [[ "$FORCE_MODE" == "kubeprom" ]]; then
  NS="monitoring"; SVC="prometheus-stack-kube-prom-prometheus"; PORT=9090
  PROVIDER="prometheus"; LABEL="kube-prometheus-stack (forced)"
else
  # Auto-detect: try kube-prom first, fall back to lgtm.
  if kubectl --context "$CONTEXT" get svc -n monitoring prometheus-stack-kube-prom-prometheus >/dev/null 2>&1; then
    NS="monitoring"; SVC="prometheus-stack-kube-prom-prometheus"; PORT=9090
    # OpenLens ships a built-in `prometheus` provider that knows the
    # community Prometheus query layout (no `/prometheus` prefix needed).
    PROVIDER="prometheus"; LABEL="kube-prometheus-stack (auto-detected)"
  else
    NS="infra"; SVC="lgtm"; PORT=9009; PROVIDER="lens"
    LABEL="lgtm Mimir (no kube-prom-stack found)"
  fi
fi

echo "▸ Target: $NS/$SVC:$PORT  ($LABEL)"

# Python handles JSON merging safely (jq doesn't always round-trip unicode / indent).
python3 <<EOF
import json, os, sys
store_path = "$STORE"
context = "$CONTEXT"
ns = "$NS"
svc = "$SVC"
port = $PORT
provider = "$PROVIDER"

with open(store_path) as f:
    data = json.load(f)

found = False
for c in data.get("clusters", []):
    if c.get("contextName") == context:
        found = True
        c.setdefault("preferences", {})
        c["preferences"]["prometheus"] = {
            "namespace": ns,
            "service": svc,
            "port": port,
            # Empty prefix — both Mimir and the kube-prom Prometheus expose
            # /api/v1/query at the ROOT of the URL. Verified for Mimir:
            #   curl http://localhost:9009/api/v1/query?query=up           → 200 OK
            #   curl http://localhost:9009/prometheus/api/v1/query?query=up → 404
            # OpenLens prepends prefix to /api/v1/{query,query_range,...}.
            "prefix": "",
        }
        # Force a re-probe on next OpenLens connect by clearing the
        # cached metadata.prometheus result (has 'success: false' if a
        # previous probe with wrong prefix failed).
        if "prometheus" in c.get("metadata", {}):
            del c["metadata"]["prometheus"]
        c["preferences"]["prometheusProvider"] = {"type": provider}

if not found:
    print(f"✗ context {context!r} not found in OpenLens store")
    print("  Available contexts:")
    for c in data.get("clusters", []):
        print(f"    - {c.get('contextName')}")
    sys.exit(2)

with open(store_path, "w") as f:
    json.dump(data, f, indent="\t")
print(f"✓ Wrote Prometheus config for context {context!r} → {ns}/{svc}:{port} (provider={provider})")
EOF

echo ""
echo "▸ Restart OpenLens to apply:"
echo "    osascript -e 'quit app \"OpenLens\"' && sleep 2 && open -a OpenLens"
