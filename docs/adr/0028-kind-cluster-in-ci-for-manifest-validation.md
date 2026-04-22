# ADR-0028 — kind-in-CI for K8s manifest validation before GKE

- **Status**: Accepted
- **Date**: 2026-04-19
- **Related**: ADR-0022 (ephemeral GKE, €2/month), ADR-0025 (no public ingress), ADR-0026 (Spring Boot scope limit)

## Context

Our three environments (ADR-0025 port policy):

1. **Compose** — happy-path dev loop, no Kubernetes primitives.
2. **kind** — local Kubernetes-in-Docker, fully outfitted (`pf-kind.sh`,
   `pgweb-kind`, `overlays/local/`).
3. **GKE Autopilot** — ephemeral production-like cluster, ~13 min to
   boot, destroyed after each validation (ADR-0022 budget).

A ~30 commits / month cadence on `deploy/kubernetes/**` means we
regularly edit K8s manifests. Two bugs classes have escaped the
compose + pre-commit `kubectl kustomize` dry-run to reach live GKE in
the last month:

- `spec.scheduler` dropped in Chaos Mesh v2.7 (PodChaos CR now rejected)
- `${IMAGE_TAG}` envsubst placeholder landing as a K8s label value
  ("must be a valid RFC 1123 subdomain")

Each cost 13 min of GKE boot + rollback + fix-and-reboot — call it
30 min of wasted cycle per incident.

**kind already exists in the repo** (`bin/cluster/port-forward/kind.sh`,
`scripts/deploy-local.sh`, `run.sh k8s-local`, `overlays/local/`). It's
available but not *enforced* — dev discipline is the only trigger
today. That is not an industry-standard practice at scale: dev
discipline without automation is a slow-rot failure mode.

## Decision

**Add a CI job `test:k8s-apply` that boots a kind cluster, applies
`overlays/local/`, and waits for the core infra pods to be Ready.**
Path-filtered to `deploy/kubernetes/**` — most MRs skip it entirely.

Implementation:

- New stage `k8s` between `integration` and `package`.
- Runs on the `macbook-local` runner (Apple Silicon arm64) with host
  Docker socket — same pattern as `docker-build`, no DinD.
- Installs `kind v0.24.0` + the latest stable `kubectl` via binary
  download in `before_script` (~5 s).
- `scripts/ci-k8s-test.sh` creates a uniquely-named kind cluster
  (`ci-k8s-${CI_JOB_ID}`), applies the local overlay, greps the apply
  output for `would violate PodSecurity` warnings (a real bug class,
  not a warning), and waits `kubectl rollout status` on five
  StatefulSet/Deployment objects.
- `after_script` deletes the kind cluster unconditionally to avoid
  runner disk leaks on cancellation.

## Scope of what the job catches

| Failure mode                                     | Compose | `kubectl kustomize` (pre-commit) | kind CI |
|--------------------------------------------------|:---:|:---:|:---:|
| YAML parse errors                                | ❌  | ✅  | ✅ |
| Kustomize render errors                          | ❌  | ✅  | ✅ |
| Missing CRD (e.g. `Rollout` not installed)       | ❌  | ❌  | ✅ |
| CRD schema drift (`spec.scheduler` v2.7)         | ❌  | ❌  | ✅ |
| PodSecurity admission rejection                  | ❌  | ❌  | ✅ |
| NetworkPolicy default-deny gaps                  | ❌  | ❌  | ✅ |
| ServiceAccount / RBAC binding missing            | ❌  | ❌  | ✅ |
| readinessProbe never turns Ready (bad port/path) | ❌  | ❌  | ✅ |
| Init container can't resolve Service DNS         | ❌  | ❌  | ✅ |
| StatefulSet PVC binding                          | ❌  | ❌  | ✅ |
| Kustomize patch targets the wrong resource       | ❌  | ❌  | ✅ |
| imagePullPolicy / image arch mismatch            | ❌  | ❌  | ⚠️ (1) |

(1) Partial — the `mirador` backend pod is deliberately NOT in the
Ready-wait list. Its image is built amd64-only (`buildx --platform
linux/amd64`) and kind on the arm64 runner can't run amd64 bytes. We
skip that pod; GKE remains the source of truth for the app container
itself. Everything else (infra images are multi-arch) is validated.

## What it does NOT catch — reserved for GKE

- Workload Identity Federation / IAM binding.
- Autopilot's resource-class mutation (Autopilot rounds up requests).
- LGTM / Unleash pod behaviour under real OTLP load.
- Cross-zone pod scheduling (`topologySpreadConstraints`).
- GSM ↔ ESO sync (ESO SA on kind is dummy).

GKE continues to be the last-mile validation, with the full
`bin/cluster/demo/up.sh` cycle.

## Decision points considered (and rejected)

| Alternative                                   | Why rejected                                                                                                 |
|-----------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| **Pre-push hook in lefthook** (auto-run kind on `git push`) | Adds 2–3 min to every push, not just K8s-touching ones. Frustration + eventual `--no-verify`. |
| **Shared dev GKE cluster** (always running)   | €190/month (ADR-0022 kills this).                                                                            |
| **Keep manual `run.sh k8s-local`**            | Status quo. Discipline-only trigger. Two bugs slipped through in the last month.                              |
| **Skip kind entirely, rely on GKE validation** | 13 min feedback loop vs 3 min in kind. Amortised cost of kind CI (~6–10 min/week) beats 30 min/incident on GKE. |
| **k3d or minikube instead of kind**           | kind is simplest (one binary, host Docker), supports `kubeadm` ConfigPatches, ships `kindest/node` multi-arch. |

## Consequences

### Positive

- **Faster feedback on K8s manifest changes**: 3 min in CI vs 13 min GKE round-trip.
- **Zero-discipline coverage**: every MR touching `deploy/kubernetes/**`
  is validated automatically, no developer reminder needed.
- **Catches a new bug class** (PodSecurity, NetworkPolicy, probe timing,
  CRD shape) that neither compose nor `kubectl kustomize` sees.
- **Low CI cost**: path filter means most MRs (business logic on
  `src/main/java`, UI on `mirador-ui`) never trigger the job. Estimated
  6–10 min / week of CI runner time.
- **Uses existing infrastructure**: `kind-config.yaml` and
  `overlays/local/` were already in the repo, just not enforced.

### Negative

- **Runner resource consumption**: kind + core pods ≈ 3 GB RAM + 2 CPU
  during the ~3 min job. Shared with other CI jobs on the macbook-local
  runner. Interruptible: true so it can be cancelled if needed.
- **Arch mismatch workaround**: mirador backend pod skipped on kind.
  Readers of the ADR need to understand *why* — hence the explicit
  table of "what is / isn't validated".
- **Extra moving part**: kind version, kubectl version, base image
  maintained in the CI job YAML. Renovate already handles image tag
  updates; kind is pinned via env var.

## Revisit this when

- Kind's arm64 story breaks (unlikely — official releases cover both).
- Mirador backend image becomes multi-arch (planned, not committed).
  Then we un-skip the backend pod readiness wait.
- The compat-sb4-java21 CI job starts catching the same class of bugs
  (unlikely — it's a JVM compat check, not a K8s check).
