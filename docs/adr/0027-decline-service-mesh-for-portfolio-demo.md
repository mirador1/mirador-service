# ADR-0027 — Decline service mesh (Istio / Linkerd) for the portfolio demo

- **Status**: Accepted
- **Date**: 2026-04-19
- **Related**: ADR-0015 (Argo CD / GitOps), ADR-0022 (ephemeral cluster ~€2/month), ADR-0023 (stay on Autopilot)

## Context

The canary deployment surface already in place (Argo Rollouts CR +
AnalysisTemplate on Mimir http-error-rate, commit `1e390db`) does
**replica-count** canary: 10 % → 50 % → 100 % of pods run the new
image, with traffic naturally load-balanced by the Kubernetes Service.

The more sophisticated pattern — **traffic-split canary** (10 % of
*requests* to the new version, regardless of replica count) — requires
a service mesh. The two mainstream options are **Istio** and
**Linkerd**, both CNCF / Apache 2.0, both open-source free software.

Clarification on "free": Istio and Linkerd themselves are free.
Payment enters only via:

- Enterprise distributions (Tetrate Service Bridge, Solo.io Gloo Mesh,
  Buoyant Cloud) — optional, feature-adds.
- **Infrastructure cost on GKE Autopilot**. Autopilot prices by
  `(cpu + memory) × time` per pod including sidecars.
  Istio and Linkerd both inject a proxy sidecar in every workload
  pod: roughly **50-100 m CPU + 64-128 Mi memory** per sidecar.
  For the demo footprint (~10 pods) that doubles CPU / memory
  requests cluster-wide.

Estimated bill impact (2026-04-18 pricing, europe-west1):

| Setting                                   | ~€/h  | ~€/month (demo, 10 h active) | ~€/month (24/7) |
|-------------------------------------------|-------|------------------------------|-----------------|
| Autopilot, no mesh (current ADR-0022)     | 0.26  | 2.60                         | 190             |
| Autopilot + Istio sidecars (10 pods)      | ~0.45 | 4.50                         | 330             |
| Autopilot + Linkerd sidecars (10 pods)    | ~0.40 | 4.00                         | 290             |

The ~70 % overhead on Autopilot erases the point of ADR-0022
(cost-controlled ephemeral demo).

## Decision

**Do not install a service mesh on this demo.**

- The existing replica-count canary via Argo Rollouts stays as the
  "progressive delivery" showcase.
- The "traffic-split canary" future-upgrade note previously referenced
  in ADR-0015 is withdrawn — not "deferred", withdrawn.
- The `TASKS.md` backend entry for Argo Rollouts / Flagger traffic
  split is deleted.

## Consequences

### Positive

- **Stay on the €2/month budget** (ADR-0022).
- No sidecar overhead to debug during chaos experiments.
- One fewer operator to install / reconcile / upgrade.
- Clear signal in the portfolio story: *"we know what a service mesh
  is and what it costs, and we picked the cheaper canary pattern that
  fits the project size."* — a judgment call, not an oversight.

### Negative

- Cannot demo true TrafficRouter-based canary (progressive traffic
  shifting during the Rollout). The replica-count version approximates
  it but is less smooth under load.
- No mTLS between pods. NetworkPolicies still enforce boundaries;
  Kubernetes Service ClusterIP TCP is plain. Acceptable for the
  cluster-internal demo — everything is already port-forward-only
  (ADR-0025), no external traffic ever reaches the pods.
- No per-request observability dimension (request-level tracing
  already exists via OpenTelemetry, so the mesh's contribution would
  be incremental anyway).

## Alternatives considered

| Option                                    | Why rejected                                                                                                              |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| Istio                                     | €2 → €4-5/month on Autopilot, breaks ADR-0022 budget.                                                                      |
| Linkerd                                   | Lighter than Istio, but still +50 % cost on Autopilot; operational win is marginal for 10 pods.                           |
| Cilium service mesh (sidecar-less)        | Requires replacing the Autopilot CNI — not possible on managed Autopilot.                                                  |
| Istio Ambient Mesh (ztunnel, no sidecars) | Promising long-term (sidecar-less), but still GA-track in 2026 and needs cluster-wide DaemonSet — not Autopilot-friendly. |

## Revisit this when

- GKE Autopilot starts exempting sidecars from billing (no plans
  announced as of this ADR).
- The project grows past ~50 pods where mesh ROI becomes positive.
- A specific mesh feature (mTLS, request-mirroring, fault injection
  at mesh layer) becomes load-bearing in the portfolio narrative.
