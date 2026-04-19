# Cost model — what each piece actually costs

An explicit price list for every moving part of the Mirador stack.
Updated when a new component lands or when GCP/providers change their
pricing. The ADR-0022 target is ≤€2/month idle; this document is how
we keep it honest.

## How to use this table

- **Before adding a new component**, look up its row below and add its
  projected cost to your mental total. If it pushes the monthly bill
  over €10 (the alert threshold in `docs/ops/cost-control.md`), you
  should either ask why, cut something else, or raise the budget
  explicitly with an ADR.
- **When the bill drifts**, cross-reference the component section —
  whatever crept in since the last clean audit shows up here.
- When a cost row changes (GCP price list update), bump it here
  together with the live `bin/budget.sh set <amount>` call. The two
  should never disagree.

Prices in EUR, europe-west1, standard tier, as of 2026-04 (spot-check
at <https://cloud.google.com/pricing>). Prices are rounded to 2
decimals or to 1 significant figure for small amounts.

## Idle baseline (cluster DOWN, nothing running)

| Component | Unit | Monthly cost | Notes |
|---|---|---|---|
| GCS bucket `*-tf-state` | 182 B | <€0.01 | tf state + lock, single-region, 1 object |
| Artifact Registry images | ~500 MB | ~€0.05 | PD-backed repo storage in europe-west1 |
| Secret Manager | 5 active secrets | €0 | free tier = 6 active secrets/month |
| Pub/Sub topic `mirador-budget-kill` | 1 topic, 0 msg | €0 | billed per-message; no message = no cost |
| Cloud Function `budget-kill` | 0 invocations | €0 | gen2 scale-to-zero, billed per invocation + vCPU-s |
| IAM bindings | — | €0 | always free |
| DNS zones (DuckDNS external) | — | €0 | external free service |
| **TOTAL idle** | — | **~€0.10** | genuine idle, ephemeral pattern at rest |

## Per-demo transient (cluster UP for 2h)

| Component | Rate | Per 2h demo | Notes |
|---|---|---|---|
| GKE Autopilot pods | €0.02 / pod-h | €0.60 | 15 pods × 2h — billed per declared resource request |
| PD-balanced for PVCs | €0.048 / GB-month | €0.01 | 64 GB × 2h = 0.17 GB-month |
| Ingress Load Balancer | €0.025 / h | €0.05 | 1 LB, 2h — N/A if ADR-0025 stays (no ingress) |
| Egress (image pulls, OTel) | — | ~€0.02 | rare, a few MB of inter-zone traffic |
| Cloud SQL (if enabled) | — | €0 | deliberately disabled; in-cluster Postgres |
| **TOTAL per demo** | — | **~€0.70** | wall-clock cluster time × component rates |

## At 5 demos/month (real portfolio cadence)

Idle baseline × 1 month + 5 × per-demo =
**~€0.10 + 5 × €0.70 = €3.60/month**.

This slightly overshoots the ADR-0022 €2 target because the
per-demo pod-hour assumption there was 1h not 2h. Either:
- Shrink demos to 1h — tight but feasible, runtime is 8min cold start
  + 15min demo-ready window + 37min to spare.
- Accept €3.60/month, update ADR-0022 with the measurement.

The point of this doc is that either choice is **deliberate**, not
surprise-inducing.

## Per-component cost rationale (for reviewers)

The table above is the fact sheet. The reasoning below is why each
component earns its line.

### GKE Autopilot vs GKE Standard

Autopilot at 15 pods × 2h × 5 demos = 150 pod-hours × €0.02 = €3/month.
GKE Standard 1 × e2-small always-on = €30/month. Autopilot wins at
our usage because we're <20% duty cycle; it would lose at a real-
traffic always-on app. Tracked in [ADR-0022](../adr/0022-ephemeral-cluster.md).

### In-cluster Postgres vs Cloud SQL

Cloud SQL db-f1-micro = €7/month, db-custom-1-3840 = €50/month. In-
cluster Postgres on a PVC = included in the pod-hour rate above, so
effectively €0 incremental. The trade-off is we lose PITR + managed
backups; the demo doesn't need them. ADR-0013.

### Cloud Function `budget-kill` vs always-on listener

Function: €0 at rest. A Compute VM e2-micro listening to Pub/Sub 24/7
= €5/month. The Cloud Function wins because the listen-on-demand
pattern is free until invoked; a VM would nullify the point of
budget-kill. See `docs/ops/cost-control.md` for the full rationale.

### Artifact Registry vs Docker Hub public

Artifact Registry: €0.05/month for our image. Docker Hub public: free
but rate-limits pulls from GKE without a registered account, which
has bit us once in CI. €0.05 is cheaper than the hour wasted diagnosing
a 429 from Docker Hub.

### LGTM bundle (Grafana) vs Grafana Cloud

Self-hosted LGTM (Tempo + Loki + Mimir + Grafana + Pyroscope in one
container): included in the 15-pod count, ~€0.40/demo. Grafana Cloud
free tier = free up to 10k series / 50 GB logs — easily exceeded with
full OTel instrumentation on a real workload. Self-host wins while
demo volume is small; Grafana Cloud wins once we have real users.

## What doesn't appear here

- **Human time.** The script `bin/mirador-doctor` + runbooks exist
  specifically to make operator time cheap, but they're not a GCP
  line item.
- **Email / notifications.** GCP budget alerts' default email is free.
- **Renovate bot runs.** Hosted by Mend, free tier.
- **gitlab.com shared runners.** Not used — we run on macbook-local
  (ADR-0004), zero quota consumed.
- **DuckDNS.** External free service for the prod DNS, outside GCP
  pricing entirely.

## When to revisit

- Any single component forecast crosses **€5/month** → open an ADR
  on the trade-off before the change lands.
- Actual bill crosses €10 → budget-kill fires, cluster destroyed,
  incident report in `docs/ops/runbooks/` with the line item that
  caused the drift.
- GCP publishes a price update for europe-west1 → refresh the
  relevant row here, update `bin/budget.sh` if the cap changes.
