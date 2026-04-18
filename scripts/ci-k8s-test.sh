#!/usr/bin/env bash
# =============================================================================
# scripts/ci-k8s-test.sh — validate K8s manifests in kind before GKE.
#
# Called by the `.gitlab-ci.yml` job `test:k8s-apply`. Runs on the
# macbook-local runner (arm64) with host Docker socket mounted.
#
# What this catches that compose misses (see docs/architecture/environments-
# and-flows.md for the full list):
#   - PodSecurity "restricted" admission rejection
#   - NetworkPolicy default-deny + missing allow rules
#   - RBAC gaps (ServiceAccount / Role / RoleBinding)
#   - CRD shape drift (Rollout / Chaos experiments / ExternalSecret)
#   - Kustomize overlay patches that don't match any target
#   - Probe timing (liveness / readiness / startup)
#   - SecurityContext violations (readOnlyRootFilesystem, runAsNonRoot, …)
#
# What it does NOT catch — reserved for GKE:
#   - Workload Identity Federation / IAM
#   - Autopilot resource-class mutation
#   - LGTM under real OTLP load
#   - Anything Cloud-SQL-shaped (we don't use it here anyway)
#
# Exit non-zero on any failure; the after_script in CI always tears down
# the cluster to avoid leaked Docker containers on the runner.
# =============================================================================

set -euo pipefail

KIND_CLUSTER="${KIND_CLUSTER:-ci-k8s-${CI_JOB_ID:-local}}"
KIND_CONFIG="${KIND_CONFIG:-deploy/kubernetes/kind-config.yaml}"
OVERLAY="${OVERLAY:-deploy/kubernetes/overlays/local}"
TIMEOUT="${TIMEOUT:-5m}"

# Infra pods we expect to become Ready. Deliberately skip the mirador
# backend — its image is built for amd64 only (buildx --platform linux/amd64
# per CLAUDE.md), and the macbook-local runner is arm64. Forcing kind to
# pull an amd64 image results in "exec format error"; cross-arch rebuild
# for CI would add ~5 min for marginal extra coverage.
#
# For the same reason: skip `unleash` if its image doesn't ship arm64.
# Seen in the Hub tag list for 7.6.3; check at run-time and skip gracefully.
CORE_PODS=(
  "infra/statefulset/postgresql"
  "infra/deployment/redis"
  "infra/deployment/kafka"
  "infra/deployment/lgtm"
  "infra/deployment/keycloak"
)

echo "🛠  Creating kind cluster $KIND_CLUSTER…"
kind create cluster --name "$KIND_CLUSTER" --config "$KIND_CONFIG" --wait 2m

echo "📋  kubectl version"
kubectl version --client=true --output=yaml | head -3
kubectl cluster-info

echo "🚀  kubectl apply -k $OVERLAY"
# The server-side apply pass catches schema errors the `kubectl kustomize`
# render in the pre-commit hook can't — missing CRDs, admission webhooks
# rejecting resources, etc.
if ! kubectl apply -k "$OVERLAY" 2>&1 | tee /tmp/apply.log; then
  echo "❌  kubectl apply failed (see log above)."
  exit 1
fi

# Grep for the "Warning: would violate PodSecurity" lines — those are the
# kind of drift the admission webhook catches in production but doesn't
# block on compose. Fail the job if any appear.
if grep -q "would violate PodSecurity" /tmp/apply.log; then
  echo "❌  PodSecurity admission would reject one or more resources on this cluster:"
  grep "would violate" /tmp/apply.log
  exit 1
fi

echo "⏳  Waiting for core pods to become Ready (timeout=$TIMEOUT)…"
failed=0
for target in "${CORE_PODS[@]}"; do
  IFS='/' read -r ns kind name <<< "$target"
  printf "  %-40s " "$target"
  if kubectl rollout status "$kind/$name" -n "$ns" --timeout="$TIMEOUT" >/dev/null 2>&1; then
    echo "✅"
  else
    echo "❌"
    kubectl describe "$kind/$name" -n "$ns" | tail -30
    failed=$((failed + 1))
  fi
done

if [ "$failed" -gt 0 ]; then
  echo "❌  $failed core pod(s) did not reach Ready. Cluster state:"
  kubectl get pods -A | grep -v Running
  exit 1
fi

echo ""
echo "✅  K8s manifests applied cleanly and ${#CORE_PODS[@]} core pods are Ready."
echo "   (mirador backend pod deliberately skipped — image is amd64-only,"
echo "    runner is arm64. GKE validation remains the source of truth for"
echo "    the app pod itself.)"
