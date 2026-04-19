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

# --- Docker-in-Docker networking fix ---
# The GitLab runner mounts the host's Docker socket into the job container,
# so `kind` creates the control-plane container as a *sibling* of the job
# container on the host Docker daemon. The kubeconfig that `kind create`
# writes targets 127.0.0.1:<randomPort> — which from inside the job
# container points at its own loopback, not the kind control-plane.
#
# Fix (three steps, each needed for a different reason):
# 1. Connect the job container to the `kind` Docker network — makes the
#    control-plane IP reachable at L3.
# 2. Pin the control-plane hostname in /etc/hosts — Docker's embedded DNS
#    (127.0.0.11) is only baked into resolv.conf at container *start*, so
#    connecting the network later doesn't give us DNS. The TLS cert is
#    issued for the hostname, not the IP, so we resolve statically rather
#    than use --insecure-skip-tls-verify.
# 3. Switch kubeconfig to `--internal` — apiserver URL becomes
#    https://<cluster>-control-plane:6443, which now resolves.
if [ -f /.dockerenv ] || grep -q "docker\|kubepods" /proc/1/cgroup 2>/dev/null; then
  JOB_CTR=$(hostname)   # GitLab job containers use container-id as hostname
  CP_CTR="${KIND_CLUSTER}-control-plane"
  docker network connect kind "$JOB_CTR" 2>/dev/null || true
  CP_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$CP_CTR" 2>/dev/null)
  if [ -n "$CP_IP" ]; then
    echo "$CP_IP $CP_CTR" >> /etc/hosts
    echo "🔌  $JOB_CTR → kind network; $CP_CTR pinned at $CP_IP in /etc/hosts."
  else
    echo "⚠️  Could not inspect $CP_CTR on kind network — falling back to default kubeconfig."
  fi
  kind export kubeconfig --name "$KIND_CLUSTER" --internal
fi

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

# PodSecurity check — only treat *enforcement rejections* as fatal, not
# warnings. Our namespace policy (base/namespace.yaml) is deliberate:
#   • app           → enforce=restricted (app pods must be hardened)
#   • infra / obs   → enforce=baseline + warn=restricted
# The `warn=restricted` label causes kubectl to emit "Warning: would
# violate PodSecurity" lines at apply time for off-the-shelf infra
# charts (Kafka, Keycloak, LGTM) that legitimately aren't restricted-
# ready. Those are informational — the pods are still admitted under
# the baseline policy. Treating them as CI failures would mean a
# policy drift: either we move infra to restricted (would require
# non-trivial patches), or we silently tolerate them (what we do now).
#
# Real enforcement rejections show up as "Error from server
# (Forbidden): ... violates PodSecurity" — those we DO fail on.
if grep -qE "Error from server \(Forbidden\).*violates PodSecurity" /tmp/apply.log; then
  echo "❌  PodSecurity admission *rejected* one or more resources:"
  grep -E "Forbidden.*PodSecurity" /tmp/apply.log
  exit 1
fi
# Still surface the warnings for visibility so we don't silently let
# a restricted-enforced namespace (app) accumulate drift.
if grep -q "would violate PodSecurity" /tmp/apply.log; then
  echo "ℹ️  PodSecurity warnings (informational — baseline policy in effect):"
  grep "would violate PodSecurity" /tmp/apply.log | head -3
  echo "   …$(grep -c 'would violate PodSecurity' /tmp/apply.log) total."
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
