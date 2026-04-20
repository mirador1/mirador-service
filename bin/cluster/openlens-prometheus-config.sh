#!/usr/bin/env bash
# =============================================================================
# bin/cluster/openlens-prometheus-config.sh
#
# Wires OpenLens's "Metrics" feature to the lgtm stack's built-in Prometheus
# (via Mimir) so CPU/memory graphs appear on kubernetes resources in the
# kind-mirador-local cluster.
#
# Why this script exists:
# OpenLens stores per-cluster Prometheus config in its local state file
# (lens-cluster-store.json) under ~/Library/Application Support/OpenLens/.
# That file is host-local and gitignored. This script writes the known-good
# config so the setup is reproducible across machines without clicking
# through OpenLens → Settings → Metrics → Prometheus service.
#
# What it sets:
#   - Prometheus service: infra/lgtm:9009/prometheus
#     (Mimir listens on 9090 internally; service maps 9009 → 9090. The
#     /prometheus prefix is Mimir's Prometheus-API mount point.)
#   - Provider: lens (the generic "Prometheus URL" mode — no preset matches
#     lgtm's Mimir-backed embed).
#
# Usage:
#   bin/cluster/openlens-prometheus-config.sh
#   # Restart OpenLens after running
#
# Prerequisite:
#   - OpenLens is installed and has been opened at least once (so the
#     lens-cluster-store.json exists).
#   - The kind-mirador-local cluster has been imported into OpenLens
#     (usually automatic when kubeconfig has the context).
#
# Companion fix: deploy/kubernetes/base/observability/lgtm.yaml now sets
# the mimir service's targetPort to 9090 (was 9009). Without that fix the
# service routes to a closed port in the pod and OpenLens gets
# ECONNREFUSED regardless of this config.
# =============================================================================

set -euo pipefail

STORE="$HOME/Library/Application Support/OpenLens/lens-cluster-store.json"
CONTEXT="${1:-kind-mirador-local}"

if [[ ! -f "$STORE" ]]; then
  echo "✗ OpenLens store not found at $STORE"
  echo "  Open OpenLens once, then re-run."
  exit 1
fi

# Python handles JSON merging safely (jq doesn't always round-trip unicode / indent).
python3 <<EOF
import json, os, sys
store_path = "$STORE"
context = "$CONTEXT"
with open(store_path) as f:
    data = json.load(f)

found = False
for c in data.get("clusters", []):
    if c.get("contextName") == context:
        found = True
        c.setdefault("preferences", {})
        c["preferences"]["prometheus"] = {
            "namespace": "infra",
            "service": "lgtm",
            "port": 9009,
            # Empty prefix — Mimir's Prometheus-compat API is at the ROOT of
            # /api/v1/query, NOT under /prometheus/api/v1/query. Verified:
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
        c["preferences"]["prometheusProvider"] = {"type": "lens"}

if not found:
    print(f"✗ context {context!r} not found in OpenLens store")
    print("  Available contexts:")
    for c in data.get("clusters", []):
        print(f"    - {c.get('contextName')}")
    sys.exit(2)

with open(store_path, "w") as f:
    json.dump(data, f, indent="\t")
print(f"✓ Wrote Prometheus config for context {context!r} → infra/lgtm:9009/prometheus")
EOF

echo ""
echo "▸ Restart OpenLens to apply:"
echo "    osascript -e 'quit app \"OpenLens\"' && sleep 2 && open -a OpenLens"
