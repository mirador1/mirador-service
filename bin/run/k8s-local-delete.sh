#!/usr/bin/env bash
# bin/run/k8s-local-delete.sh — implements `./run.sh k8s-local-delete`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    KIND_CLUSTER="mirador"
    echo "Deleting kind cluster '${KIND_CLUSTER}' and all its resources..."
    kind delete cluster --name "${KIND_CLUSTER}"
    echo "Cluster deleted."
