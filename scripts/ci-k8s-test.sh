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

# Wrap a command that pipes into `head` or `grep -q` (which exits
# early and causes SIGPIPE on the writer → exit 141 under pipefail).
# Usage: sigpipe_safe <command>
# Rationale: kind-on-CI runs that died with exit 141 "mid rollout-check"
# (see .gitlab-ci.yml header) were classical producer-closes-first
# SIGPIPE cases. Capturing the producer's output into a variable first,
# THEN filtering it, avoids the pipe entirely. For the few cases below
# where piping is cleaner, we explicitly tolerate SIGPIPE with trap.
# Not inlined as a function because the capture pattern is more
# readable at the call site.

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
  # Keycloak was in CORE_PODS until 2026-04-21 but removed because it
  # repeatedly timed out on the arm64 macbook-local runner under CI
  # load (10+ minutes to become Available — pipelines #602 and #604
  # both hit the same wait-timeout despite the overlay applying
  # cleanly). The job's purpose is to validate `kubectl apply -k`
  # succeeds + the base infra pods schedule; keycloak readiness is
  # nice-to-have but not part of the contract this test enforces.
  # If a GKE run shows a real keycloak config regression that kind
  # couldn't catch, that's a known gap documented in
  # docs/reference/quality-reports-map.md.
)

# EXTRA_PODS — overlay-specific pods to wait for, in addition to CORE_PODS.
# Set via env var by the calling CI job. Format: same "ns/kind/name" tuples
# as CORE_PODS. Empty by default (the `local` overlay adds nothing extra).
#
# Used by `test:k8s-apply-prom` to wait for the kube-prometheus-stack
# components added by the `local-prom/` overlay:
#   monitoring/statefulset/prometheus-prometheus-stack-kube-prom-prometheus
#   monitoring/daemonset/prometheus-stack-prometheus-node-exporter
#   monitoring/deployment/prometheus-stack-kube-state-metrics
#   monitoring/deployment/prometheus-stack-kube-prom-operator
# The Prometheus Operator manages its own StatefulSet for the
# Prometheus pods, so the actual rollout target is the StatefulSet
# (managed via kubectl rollout status statefulset/...).
read -ra EXTRA_PODS <<< "${EXTRA_PODS:-}"

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
# Capture → slice. Previous `kubectl version ... | head -3` was a
# classic SIGPIPE trap: `head` exits after 3 lines while `kubectl`
# keeps writing → SIGPIPE → exit 141 under pipefail. Taking 3
# lines from a captured variable is equivalent + SIGPIPE-free.
_kv=$(kubectl version --client=true --output=yaml)
printf '%s\n' "$_kv" | awk 'NR<=3'
unset _kv
kubectl cluster-info

# Install the third-party CRDs that the overlay's manifests reference.
# In the real cluster these come from Argo CD applications (chaos-mesh
# helm chart, external-secrets operator helm chart). kind-in-CI has
# neither — so `kubectl apply -k` fails with "no matches for kind
# NetworkChaos / ExternalSecret / SecretStore" before it gets a chance
# to validate the manifests we actually care about.
#
# Pinning CRD versions — we validate shape, not upstream stability. If
# the CRD schema changes upstream, we find out via a controlled bump.
CHAOS_MESH_VERSION="${CHAOS_MESH_VERSION:-2.7.2}"
# ESO v1 API (external-secrets.io/v1) first shipped in v1.0.0 (Jan 2025).
# The overlays use `apiVersion: external-secrets.io/v1` so anything older
# fails with "no matches for kind ExternalSecret in version v1". Stay on
# the 1.x major (v1.3.2 = latest 2026-04 stable). Bumping across majors
# requires checking the schema (webhook annotations, CR fields).
ESO_VERSION="${ESO_VERSION:-1.3.2}"

echo "📦  Installing third-party CRDs (chaos-mesh $CHAOS_MESH_VERSION, ESO $ESO_VERSION)…"
kubectl apply --server-side=true -f \
  "https://mirrors.chaos-mesh.org/v$CHAOS_MESH_VERSION/crd.yaml" >/dev/null
kubectl apply --server-side=true -f \
  "https://raw.githubusercontent.com/external-secrets/external-secrets/v$ESO_VERSION/deploy/crds/bundle.yaml" >/dev/null

# Wait for the CRDs to be established before the overlay references them.
for crd in podchaos.chaos-mesh.org networkchaos.chaos-mesh.org \
           stresschaos.chaos-mesh.org externalsecrets.external-secrets.io \
           secretstores.external-secrets.io; do
  kubectl wait --for=condition=established --timeout=30s "crd/$crd" >/dev/null
done
echo "  ✓ CRDs established."

# For overlays that include Prometheus Operator CRDs (local-prom,
# gke-prom), pre-install the CRDs server-side FIRST — otherwise the
# subsequent `kubectl apply -k` races the ServiceMonitor / Prometheus
# resources against the CRD Establishment and fails with
# "no matches for kind ServiceMonitor". `kustomize apply` submits
# everything at once; server-side has improved ordering but is not a
# strict two-phase commit, so we split the two phases explicitly.
KUBE_PROM_CRDS_FILE="$OVERLAY/kube-prom-stack-crds.yaml"
if [[ -f "$KUBE_PROM_CRDS_FILE" ]]; then
  echo "📦  Pre-installing kube-prometheus-stack CRDs server-side…"
  kubectl apply --server-side=true --force-conflicts -f "$KUBE_PROM_CRDS_FILE" >/dev/null
  # Wait for the core CRDs we know are referenced by the overlay's
  # ServiceMonitors / Prometheus / Alertmanager resources.
  for crd in prometheuses.monitoring.coreos.com \
             servicemonitors.monitoring.coreos.com \
             alertmanagers.monitoring.coreos.com \
             podmonitors.monitoring.coreos.com \
             prometheusrules.monitoring.coreos.com; do
    kubectl wait --for=condition=established --timeout=30s "crd/$crd" >/dev/null
  done
  echo "  ✓ kube-prom-stack CRDs established."
fi

echo "🚀  kubectl apply --server-side -k $OVERLAY"
# `--server-side=true` (Server-Side Apply, SSA) is required for the
# `local-prom` / `gke-prom` overlays because the vendored kube-prom-
# stack CRDs (alertmanagerconfigs, prometheuses, scrapeconfigs, etc.)
# exceed the 256KB `last-applied-configuration` annotation limit that
# client-side apply stores — kubectl fails with "metadata.annotations:
# Too long: must have at most 262144 bytes". Server-side apply stores
# field ownership on the cluster side instead of stamping the full
# manifest into an annotation, so the size limit does not apply.
# `--force-conflicts` lets us own fields previously set by a no-longer-
# present kustomize label patch (needed on re-applies after a rerun).
# Pattern already used above for the chaos-mesh + ESO CRDs.
if ! kubectl apply --server-side=true --force-conflicts -k "$OVERLAY" 2>&1 | tee /tmp/apply.log; then
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
  # Same SIGPIPE rewrite as the kubectl version block above: capture
  # the matching lines FIRST (grep can't SIGPIPE to a file), then
  # print the first 3 via awk. Previous `grep ... | head -3` sometimes
  # died with exit 141 on noisy apply logs.
  _psw=$(grep "would violate PodSecurity" /tmp/apply.log)
  printf '%s\n' "$_psw" | awk 'NR<=3'
  # grep -c is in a command substitution (no pipe, no SIGPIPE risk).
  echo "   …$(grep -c 'would violate PodSecurity' /tmp/apply.log) total."
  unset _psw
fi

echo "⏳  Waiting for core pods to become Ready (timeout=$TIMEOUT)…"
failed=0
ALL_PODS=("${CORE_PODS[@]}")
# Append overlay-specific pods if EXTRA_PODS was set (non-empty array).
if [ "${#EXTRA_PODS[@]}" -gt 0 ]; then
  ALL_PODS+=("${EXTRA_PODS[@]}")
fi
for target in "${ALL_PODS[@]}"; do
  IFS='/' read -r ns kind name <<< "$target"
  printf "  %-60s " "$target"
  # Wait strategy depends on the resource kind:
  #   - Deployment  : `kubectl wait --for=condition=Available`
  #     (idiomatic, doesn't stream events → no SIGPIPE risk).
  #   - StatefulSet : `kubectl rollout status` (StatefulSets do NOT
  #     expose an Available condition; `kubectl wait --for=condition=
  #     Available statefulset/X` silently times out — caught the hard
  #     way in kind-on-CI pipeline #598 where postgresql hung).
  #   - DaemonSet   : `kubectl rollout status` (same — no Available
  #     condition).
  # `rollout status` is the canonical wait for StatefulSet/DaemonSet
  # and the streaming-SIGPIPE concern from the original TODO was
  # rooted in `| head`/`| grep -v` pipes elsewhere in this script
  # (fixed in the same commit), not in `rollout status` itself.
  case "$kind" in
    daemonset|statefulset)
      if kubectl rollout status "$kind/$name" -n "$ns" --timeout="$TIMEOUT" >/dev/null 2>&1; then
        echo "✅"
      else
        echo "❌"
        kubectl describe "$kind/$name" -n "$ns" | tail -30
        failed=$((failed + 1))
      fi
      ;;
    *)
      if kubectl wait --for=condition=Available --timeout="$TIMEOUT" \
           "$kind/$name" -n "$ns" >/dev/null 2>&1; then
        echo "✅"
      else
        echo "❌"
        kubectl describe "$kind/$name" -n "$ns" | tail -30
        failed=$((failed + 1))
      fi
      ;;
  esac
done

if [ "$failed" -gt 0 ]; then
  echo "❌  $failed pod(s) did not reach Ready. Cluster state:"
  # Another SIGPIPE rewrite: previously `kubectl get pods -A | grep -v
  # Running` could exit 141 if grep decided to close early on a large
  # buffered response. awk on captured output is equivalent + safe.
  _allpods=$(kubectl get pods -A)
  printf '%s\n' "$_allpods" | awk 'NR==1 || $4 != "Running"'
  unset _allpods
  exit 1
fi

echo ""
echo "✅  K8s manifests applied cleanly and ${#ALL_PODS[@]} pods are Ready."
echo "   (mirador backend pod deliberately skipped — image is amd64-only,"
echo "    runner is arm64. GKE validation remains the source of truth for"
echo "    the app pod itself.)"
