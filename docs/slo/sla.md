# Mirador Service — SLA (Service Level Agreement)

> ⚠️ **Portfolio-demo SLA**. This is not a real customer-facing contract —
> it's a documented commitment to demonstrate the production-grade discipline
> that a real SLA would require. The math + tooling is real ; the
> "consequences" section is illustrative.

## What we promise

| Indicator | Target | Window | Error budget |
|---|---|---|---|
| **Availability** (HTTP 200-499 / total) | 99.0% | 30 days | 432 min downtime/month |
| **Latency p99** (request duration < 500ms) | 99.0% | 30 days | 1% of requests can be slow |
| **Customer enrichment success** (no 504) | 99.5% | 30 days | 0.5% of /enrich calls can timeout |

Numbers chosen to be REALISTIC for a single-instance demo on shared infra
(GKE Autopilot or OVH) — NOT pretend-five-nines that wouldn't survive a
single Kubernetes pod restart.

## How we measure

- **Recording rules** : `sloth generate` produces 6-window rules (5m → 3d)
  for each SLO at evaluation time. See `mirador-slo.yaml` in the shared
  submodule's `deploy/kubernetes/observability-prom/`.
- **Source metrics** : Spring Boot Actuator → Micrometer → Prometheus
  (`http_server_requests_seconds_count` + `_bucket`).
- **Multi-window multi-burn-rate alerting** (Google SRE Workbook ch. 5) :
  - **Page** : 1h fast-burn (14.4×) → 2% budget in 1h
  - **Page** : 6h slow-burn (6×) → 5% budget in 6h
  - **Ticket** : 1d (3×) → 10% budget in 1d
  - **Ticket** : 3d (1×) → 10% budget in 3d
- **Dashboard** : Grafana "SLO Overview" — burn rate gauge, error budget
  remaining, time-to-exhaustion projection.

## Consequences (illustrative — portfolio demo)

In a real SLA :
- **Page alert fires** → on-call engineer responds within 15 min.
- **Budget below 50%** → freeze all non-essential deploys.
- **Budget exhausted** → post-mortem within 7 days, follow-up actions
  tracked in TASKS.md.
- **Recurring breach** (3 consecutive months) → architectural review,
  possibly tighten or relax the SLO based on capacity.

For this demo : breach triggers a desktop notification + a runbook link
(`docs/runbooks/slo-*.md`). Recovery practiced via `/customers/diagnostic/*`
chaos endpoints.

## What we DON'T cover

- **Network latency** between client and edge (out of our control).
- **GitLab Pages** + documentation site (separate infra, no SLA).
- **Manual operations** (deploys, migrations, rollbacks) — those have
  their own runbook checklists, not SLO-tracked.
- **Third-party dependencies** (Kafka broker, Postgres, Redis, OTel
  Collector) — degraded gracefully (best-effort), error budget not
  attributed to upstream outages.

## Review cadence

- **Monthly** : SLO compliance review (target % achieved, budget consumed,
  top contributing endpoints).
- **Quarterly** : SLO target review (tighten if always green, relax if
  always red).
- **Post-incident** : SLO budget impact assessment, alert tuning.

## References

- [`slo.yaml`](slo.yaml) — Sloth-format SLO definitions, source-of-truth.
- [Google SRE Workbook chapter 5 — Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/)
- [Sloth documentation](https://sloth.dev/)
- ADR-0007 (industrial Python practices) — same observability stack on Python side
- ADR-0010 (OTLP push to Collector) — metrics pipeline
