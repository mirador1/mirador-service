# ADR-0023: Stay on GKE Autopilot (over GKE Standard)

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

After the monthly-cost audit (ADR-0021 update) showed ~€190/month for
the current Autopilot cluster running 24/7, one option was to migrate
to GKE Standard: rent a single small node (e.g. e2-small ~€13/month)
plus the cluster-management fee (~€17/month after the free tier),
total ~€30–40/month always-on. That's cheaper than Autopilot 24/7.

The other option — the one picked — is **stay on Autopilot but make
the cluster ephemeral** (ADR-0022), bringing the monthly bill to ~€2
for a couple of demo hours.

Now the question is: would GKE Standard be a better fit for the
ephemeral pattern too?

## Decision

**Keep GKE Autopilot.** Simplicity of administration wins over the
flexibility GKE Standard would add.

Concretely, Autopilot:

- Manages node pools, upgrades, bin-packing, node auto-repair.
  No `gcloud container node-pools` to maintain; no version pinning
  decisions; no "am I on the right machine type" questions.
- Applies the GKE security baseline by default (PodSecurity
  `restricted`, Workload Identity required, shielded nodes,
  automatic OS patching).
- Exposes a single billing dimension — pod resource requests —
  rather than node counts × machine types × hourly rates that each
  need to be rightsized.

With the ephemeral pattern (ADR-0022) the main Autopilot drawback
(higher per-hour pod cost than a raw VM) is neutralised: we pay that
premium for ~8 hours per month instead of 730.

## Alternatives considered

**GKE Standard with 1 × e2-small node (always up)**:
- Always-up cost: ~€30–40/month.
- **Against**: demo-up / demo-down are no longer symmetrical
  (the node pool always exists), which means drift can accumulate
  between demos. Also introduces node-pool upgrade cadence to
  manage.

**GKE Standard with node-pool scale-to-zero + ephemeral pattern**:
- Gets the €0/month when-down story too, with more control over
  node machine types (e.g. spot preemptibles).
- **Against**: scaling a node pool to zero + spinning back up adds
  one more step to `bin/cluster/demo/up.sh` (wait for a node), and the
  first pod to schedule takes ~2 min longer. Autopilot's provisioner
  handles this natively.

**k3s on Hetzner Cloud VM €4/month**:
- Dirt cheap, always up, covers every Kubernetes pattern.
- **Against**: leaves the GCP / GKE ecosystem that the portfolio
  is meant to showcase (Workload Identity, GSM, Cloud Audit Logs,
  Artifact Registry).

## Consequences

Positive:
- Less cognitive overhead per demo — `terraform apply` on a single
  `google_container_cluster` resource and you're done.
- Security baselines you'd have to wire up manually on Standard
  (PodSecurity admission, Workload Identity, shielded nodes) are the
  defaults on Autopilot.
- The `bin/cluster/demo/up.sh` / `bin/cluster/demo/down.sh` flow stays symmetrical:
  create a cluster or destroy it, nothing in between.

Negative:
- **Autopilot constraints propagate**:
  - No custom CNI (Cilium, Calico with custom config). Hubble L7
    observability therefore drops from the nice-to-have list.
  - Pod resource requests are rounded up to Autopilot's minimums
    (we saw the default ~500m CPU / 2 GiB memory footprint during
    MR 64 before we shrank everything explicitly).
  - `hostPath` volumes, privileged containers, kernel modules all
    rejected by default.
- Anyone forking the demo for a non-GCP cloud has to switch to the
  `eks` / `aks` / `local` overlays; the `gke` overlay is tied to
  Autopilot's defaults.

## What this means for the nice-to-have tier (ADR-0022 update)

| Item | Autopilot-compatible? |
|---|---|
| LGTM stack in-cluster (`grafana/otel-lgtm`) | ✅ single pod, no hostPath |
| Grafana dashboards via grizzly | ✅ pure YAML |
| Sentry SaaS free tier | ✅ no cluster side |
| GitLab Pages landing page | ✅ no cluster side |
| Pyroscope self-hosted | ✅ pure pod + PVC |
| Kyverno (admission policies) | ✅ CRDs + webhooks |
| Unleash (feature flags) | ✅ pod + Postgres |
| Chaos Mesh | ✅ supports Autopilot (since 2.7) |
| Argo Rollouts | ✅ controller + CRDs |
| Cilium CNI | ❌ **needs GKE Standard** — dropped from the roadmap until/unless we migrate |
| Istio / Linkerd | ✅ works on Autopilot (with slightly higher memory overhead) — deferred per ADR-0021 for demo-length reasons, not Autopilot reasons |

## References

- ADR-0021 — cost-deferred industrial patterns
- ADR-0022 — ephemeral demo cluster
- <https://cloud.google.com/kubernetes-engine/docs/resources/autopilot-standard-feature-comparison>
- <https://chaos-mesh.org/docs/deploy-chaos-mesh-on-gke/> — Chaos
  Mesh Autopilot note
