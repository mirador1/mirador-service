# ADR-0022: Ephemeral demo cluster (bring up on demand)

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

The 24/7 GKE Autopilot cluster was costing **~€190/month** in pod
billing — see the ADR-0021 ran-the-numbers update. That's too much for
a single-developer portfolio demo that only runs live for a few hours
a month.

GKE Autopilot has no "stop" verb: you can't scale a cluster to zero
nodes like you can with a GKE Standard node pool, because Autopilot
manages the pool itself. The only way to stop paying for Autopilot
pods is to **delete the cluster**.

## Decision

**The cluster exists only during a demo.** Default state = deleted.
Two scripts wrap the lifecycle:

```
bin/cluster/demo/up.sh     # terraform apply + bootstrap Argo CD + ESO  (~8 min)
bin/cluster/demo/down.sh   # terraform destroy                          (~5 min)
```

Everything that **must survive** across demos lives outside the
Terraform state:

| Resource | Lifecycle | Persistence |
|---|---|---|
| GCS Terraform state bucket | One-off | Cents/month |
| GSM secrets (5 entries) | One-off | €0 (under free tier) |
| GCP service account `external-secrets-operator@` | One-off | €0 |
| GCP IAM bindings for GSM + WIF | One-off | €0 |
| Artifact Registry images | Push-on-release | Cents/month |
| K8s cluster, pods, PVCs, Secrets | Ephemeral | **€0 when down, €0.26/h when up** |

`bin/cluster/demo/up.sh` re-wires the Workload Identity annotation on every
boot because the K8s ServiceAccount is new every time; the GCP side
is already bound.

## Cost math

| Scenario | Hourly | Monthly |
|---|---|---|
| Cluster up 24/7 | €0.26 | ~€190 |
| Cluster up 2h/week (demo) | €0.26 × 8 | **€2** |
| Cluster up 0h (baseline) | €0 | Cents (GCS + AR) |

Break-even with GKE Standard (1 × e2-small node always up, ~€40/mo)
happens at ~150 demo-hours/month, which is way beyond the intended
usage.

## Consequences

Positive:
- Monthly GCP bill goes from €190 to **~€0** in default posture.
- `terraform destroy` is a full reset — each demo starts from a known
  state, which IS the Terraform code + K8s overlay in git. No drift
  can accumulate between sessions.
- Bringing the cluster back takes ~8 min (one-time per demo) — fine
  because demos are scheduled, not spontaneous.

Negative:
- Postgres data, Keycloak realm, Argo CD history are re-created
  empty on every boot. Flyway repopulates the schema, Keycloak
  starts with the admin account from GSM, Argo CD shows the current
  state of `main`. No PITR, no cross-demo continuity — which is
  fine for a portfolio demo.
- Demos can't be "always on" for passing recruiters — the landing
  page needs either: a static preview on GitLab Pages (free) or a
  cheap tiny VM (Hetzner €4/mo) redirecting with a "fire up the
  cluster to see it live" button. Not in scope yet.
- TLS cert is reissued by cert-manager on every boot → 5 min delay
  before the demo URL responds with a valid cert. Mitigated by
  warming up the cluster 10 min before the demo starts.

## Alternatives considered

**GKE Standard with 1 × e2-small node, always up**:
- ~€17/month cluster management + ~€13 compute = ~€30–40/month.
- **Against**: still not €0, and requires managing node upgrades +
  release channel choices. Ephemeral is simpler **and** cheaper.

**k3s on a Hetzner Cloud VM €4/mo**:
- Cheapest 24/7 option; fits everything the demo needs.
- **Against**: leaves the GCP/GKE ecosystem that the demo is
  supposed to showcase (Workload Identity, GSM, Cloud Audit).

**Pause/resume via node-pool scale-to-zero**:
- Works on GKE Standard, not Autopilot.
- **Against**: requires migrating off Autopilot, which is its own
  decision.

## How this reshapes the "nice-to-have" SaaS bucket from ADR-0021

The nice-to-have tier (APM, error tracking, feature flags, incident
management, status page, continuous profiling) is usually covered by
monthly-fee SaaS with an always-on expectation. An ephemeral cluster
changes the calculus:

| Capability | ADR-0021 recommendation | Ephemeral-cluster impact | New recommendation |
|---|---|---|---|
| **APM** | OpenTelemetry SDK + LGTM self-hosted | LGTM dies with the cluster → historical data is lost between demos. SaaS APMs charge per-host which is cheap during a 2h demo but their UX assumes 24/7 ingestion. | **LGTM self-hosted in-cluster**. Trade 1-week retention for €0 bill; acceptable because each demo starts from a clean state anyway. |
| **Error tracking** | GlitchTip self-hosted vs Sentry free tier | Sentry free tier (5k events/mo) stays 5k even if the cluster is down most of the month — **becomes the sweet spot**. | **Sentry SaaS free tier**. Retains error history across demos out of the box, no self-hosting needed. |
| **Feature flags** | Unleash self-hosted vs LaunchDarkly paid | In-cluster Unleash disappears on `demo-down`; flag state lost. SaaS flag state survives. | **Unleash self-hosted + its PostgreSQL seeded from a `flags.sql` in git**. Keeps €0 and keeps flags deterministic per demo. |
| **Incident management** | Grafana OnCall self-hosted vs PagerDuty paid | Not really meaningful during a demo (no real paging to do). | **Defer entirely**. No SaaS, no self-hosted. Add when the cluster becomes long-lived. |
| **Status page** | Cachet self-hosted vs Statuspage.io paid | A status page for a default-off cluster is ironic. | **Static page on GitLab Pages** saying "the demo is spun up on request; reach out for a live walk-through". €0, always up, always honest. |
| **Continuous profiling** | Pyroscope self-hosted vs Grafana Cloud Profiles | Same as APM — history lost on destroy. | **Pyroscope self-hosted** alongside LGTM. Gets the CPU/mem flame graphs for the demo window. |
| **Dashboard-as-code** | grizzly/jsonnet in git | Dashboards applied on every `demo-up`. | **No change**. This is already git-tracked and applied via `bin/cluster/demo/up.sh` extension. |

Net effect: the ephemeral-cluster pattern makes **LGTM + Pyroscope
self-hosted the obvious choice** for anything observability-shaped
(nothing to pay monthly, starts with the cluster), while **small SaaS
free tiers like Sentry become attractive** for the few things where
cross-demo continuity is genuinely useful (errors, flags with a
real database).

## References

- `bin/cluster/demo/up.sh` / `bin/cluster/demo/down.sh` — the two-verb lifecycle.
- `deploy/terraform/gcp/main.tf` — single `google_container_cluster`
  resource on the default VPC (6 previously-managed resources were
  archived to `docs/archive/terraform-deferred/`).
- ADR-0021 — this is the monthly-bill-reduction half of the
  cost-deferred industrial-patterns story.
