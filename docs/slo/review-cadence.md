# SLO review cadence — Mirador Service (Java)

> **Canonical cadence lives in shared** — see
> [`infra/shared/docs/slo/review-cadence.md`](../../infra/shared/docs/slo/review-cadence.md)
> ([direct GitLab link](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/slo/review-cadence.md)).
> The shared doc covers the "Why / Cadence (monthly + quarterly +
> post-incident) / Decision matrix / Anti-patterns" — the same
> framework applies to both Java and Python services.
>
> **This file is the Java-specific addendum** : per-repo dashboards,
> incident classes that surface in this codebase, and which scripts
> to run when preparing the review.

## Quick links (Java repo)

| Asset | URL |
|---|---|
| SLO definitions | [`docs/slo/slo.yaml`](slo.yaml) |
| SLA promise | [`docs/slo/sla.md`](sla.md) |
| SLO Overview dashboard (cross-repo) | [`infra/shared/.../slo-overview.json`](../../infra/shared/infra/observability/grafana/dashboards-lgtm/slo-overview.json) — UID `mirador-slo-overview` |
| SLO Breakdown by Endpoint (Java-only) | [`infra/observability/grafana-dashboards/slo-breakdown-by-endpoint.json`](../../infra/observability/grafana-dashboards/slo-breakdown-by-endpoint.json) — UID `mirador-slo-breakdown-by-endpoint` |
| Latency Heatmap (Java-only) | [`infra/observability/grafana-dashboards/latency-heatmap.json`](../../infra/observability/grafana-dashboards/latency-heatmap.json) — UID `mirador-latency-heatmap` |
| Apdex (Java-only) | [`infra/observability/grafana-dashboards/apdex.json`](../../infra/observability/grafana-dashboards/apdex.json) — UID `mirador-apdex` |
| Runbooks | [`docs/runbooks/slo-availability.md`](../runbooks/slo-availability.md), [`slo-latency.md`](../runbooks/slo-latency.md), [`slo-enrichment.md`](../runbooks/slo-enrichment.md) |

The 3 Java-only dashboards (breakdown / heatmap / apdex) query the
Spring Boot Micrometer histogram `http_server_requests_seconds_*` and
won't render data for the Python service (which exposes
`starlette_*` series instead).

## Pre-review checklist (Java specifics)

Run these BEFORE the monthly meeting so the data is fresh :

1. **Confirm Sloth recording rules are loaded.** Promethues should
   have the `slo:current_burn_rate:ratio{sloth_id="mirador-service-*"}`
   recording rules. If empty after a deploy, regenerate
   `deploy/kubernetes/observability-prom/mirador-slo.yaml` from
   `docs/slo/slo.yaml` :
   ```
   sloth generate -i docs/slo/slo.yaml -o deploy/kubernetes/observability-prom/mirador-slo.yaml
   ```
2. **Check Hikari / Tomcat / Kafka error counters** in Grafana — these
   often reveal "near-misses" that didn't break the SLO this month
   but flag tightening risk for next quarter.
3. **Run `./mvnw -Pcoverage verify`** if coverage on the touched
   feature slices (`com.mirador.{customer,order,product}.*`) dropped
   below the per-package gates — coverage drift correlates with
   future incidents.
4. **Check the OWASP / grype CVE counts** — security debt indirectly
   burns availability budget through forced patch deploys.

## Java-specific incident classes (track separately)

When tabulating "incidents this month" for the meeting, group by
class — these are the failure modes specific to a Spring Boot +
Postgres + Kafka backend :

| Class | Symptom | Where it shows up |
|---|---|---|
| **Hikari pool exhaustion** | latency p99 spikes uniformly | `hikari_active`, `hikari_pending` |
| **Kafka consumer lag** | `/enrich` 504s | `kafka_consumer_lag_records` |
| **JVM GC pause** | sawtooth latency, brief 5xx | `jvm_gc_pause_seconds` |
| **Flyway migration deadlock** | startup probe failures | log search `FlywayException` |
| **OOMKilled** | pod restarts during peak | `kube_pod_container_status_last_terminated_reason="OOMKilled"` |
| **Auth0 / OIDC outage** | 401 spike on protected endpoints | upstream provider status page |

Compare class frequency month-over-month — recurrence in the same
class signals architectural debt, not bad luck.

## Output format (for the team)

After the meeting, the on-call lead writes 5-10 lines in
`docs/slo/review-log.md` (append-only, one entry per month) :

```
## YYYY-MM (e.g. 2026-04)

- Compliance: availability 99.91%, latency 99.87%, enrichment 99.62%.
- Top burn contributor: /customers/{id}/enrich (62% of latency burn).
- Incidents: 1 × Hikari exhaustion (12 min, 3% of availability budget).
- Decision: hold all 3 SLOs. Investigate Hikari sizing in next sprint.
- Action items: TASKS.md item "tune HikariCP maximumPoolSize for /enrich".
```

Quarterly entries go in `docs/slo/trends-YYYY-Q.md` (one file per
quarter, ~30 lines : compliance trend chart commentary, big-picture
decisions, SLA promise updates if any).

## See also

- Shared canonical cadence : [`infra/shared/docs/slo/review-cadence.md`](../../infra/shared/docs/slo/review-cadence.md)
- [ADR-0058 SLO/SLA with Sloth](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0058-slo-sla-with-sloth.md)
- [SLA promise (Java)](sla.md)
- [SLO definitions (Java)](slo.yaml)
- [Google SRE Workbook ch. 4 — Implementing SLOs](https://sre.google/workbook/implementing-slos/)
