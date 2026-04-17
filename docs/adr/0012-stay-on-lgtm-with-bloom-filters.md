# ADR-0012: Stay on LGTM with Loki bloom filters — defer OpenSearch

- **Status**: Accepted
- **Date**: 2026-04-17
- **Refines**: [ADR-0010](0010-otlp-push-to-collector.md)

## Context

The current observability stack is the `grafana/otel-lgtm` bundle:
Loki (logs), Tempo (traces), Mimir (metrics) and Grafana, fed by a
single OTel Collector. Everything flows via OTLP/HTTP — see
[ADR-0010](0010-otlp-push-to-collector.md).

Loki indexes logs by **label**, not by token. Full-text filters like
`|= "Kafka timeout"` stream through every chunk in the time range.
That's fine on 1-day retention, poor on 7+ days, unworkable on 30+.

We evaluated whether to introduce OpenSearch (or another full-text
store) alongside LGTM to close that gap. This ADR records the outcome.

## Decision

**Stay on LGTM**. Enable Loki's native bloom-filter accelerator — the
feature Grafana Labs shipped in Loki 3.0 and matured in 3.7
specifically to close the full-text gap — rather than add a second
log store.

Concretely:

1. Bump the `grafana/otel-lgtm` tag from `0.22.1` → **`0.25.0`**. This
   upgrades the bundled components to:
   - **Loki 3.7.1** (bloom filters ready for production use)
   - **Grafana 12.4.2** (Logs Drilldown polish)
   - **Tempo 2.10.4**
   - **Prometheus 3.11.2**

2. Override the baseline Loki config via
   `infra/observability/loki-override.yaml` (mounted read-only onto
   `/otel-lgtm/loki-config.yaml`). The override keeps the baseline
   schema (v13 TSDB, filesystem storage, single-instance ring) and
   adds:

   ```yaml
   bloom_build:
     enabled: true
   bloom_gateway:
     enabled: true
     client:
       addresses: 127.0.0.1:9095
   ```

3. Verified with `loki -verify-config` against the pinned image
   before landing. Per-tenant toggles under `limits_config`
   (`bloom_gateway_enable_filtering`, `bloom_build_enable`) are
   intentionally omitted here — their YAML key names drift between
   3.x minors, and the global flags above are enough to start
   building blooms at flush time.

## Why not OpenSearch

OpenSearch would undeniably give us richer full-text, Kibana-style
aggregations, vector/kNN and ML-based anomaly detection. But for
Mirador today:

- **Cost.** OpenSearch is ~3× the baseline RAM of Loki and adds a
  JVM cluster to operate. Mirador is a demo stack that has to stay
  runnable on a single MacBook.
- **Duplication.** Logs would live in Loki *and* OpenSearch, doubling
  storage and making retention decisions ambiguous.
- **Missing driver.** No user-facing search-on-logs feature is on
  the backlog. The only pain point is "find a string in yesterday's
  logs fast", which bloom filters solve.
- **Philosophy.** ADR-0006/0007 pulled the UI back to one observability
  source (Grafana). Adding OpenSearch re-fragments that.

The two conditions that WOULD flip this decision:

- A business need for **searchable customer/audit content** (fuzzy
  name/email/detail) — though pg_trgm on Postgres would be tried
  first.
- A need for **SIEM-grade correlation or ML anomaly detection** on
  logs + audit events. OpenSearch has this natively; Loki does not.

Neither is planned.

## Alternatives considered

### Alternative A — OpenSearch + Dashboards alongside LGTM

Rejected. Real full-text, aggregations, ML, vector — at the cost of
3× RAM, a JVM fleet to operate, and two log stores to reason about.
The current stack isn't constrained enough to justify that blast
radius.

### Alternative B — Quickwit

Rejected for now. Tantivy-backed, S3-native, very cheap to operate
for log full-text, and has a Grafana datasource. It would be the
first candidate IF Loki 3.7 blooms prove insufficient at scale.
Documented as a follow-up in `TASKS.md` rather than a hard "no".

### Alternative C — VictoriaLogs

Rejected for now. Drop-in log backend with Lucene-like grammar,
5–10× less RAM than Elastic, single Go binary. Attractive
"second-step" option if we want a richer full-text story without
OpenSearch's operational weight. Also in `TASKS.md` as a fallback.

### Alternative D — ClickHouse with `tokenbf_v1`

Rejected. Overkill as a pure log store, but compelling if we ever
combine "full-text on logs" + "SQL forensic queries" + "customer
data search" in one engine.

### Alternative E — Grafana LLM / Sift

Not an alternative — a UX layer on top of whichever backend we
choose. Worth revisiting once blooms are in place.

## Consequences

### Positive

- **Zero new services.** Bloom filters live inside the existing
  Loki binary; the override file is a diff against the baseline,
  not a parallel deployment.
- **LogQL unchanged.** Every existing dashboard, alert, and derived
  field keeps working; queries just get faster.
- **Predictable upgrade path.** If blooms aren't enough one day,
  Quickwit or VictoriaLogs become the next step without having
  already paid the OpenSearch tax.
- **Grafana 12.4's Logs Drilldown** ships in the same bump — closes
  a large part of the UX gap vs OpenSearch Dashboards.

### Negative

- **Marginal win at dev scale.** Blooms amortise only after several
  chunks are flushed; in short local sessions the query latency is
  indistinguishable. The feature is aimed at production volumes.
- **Experimental status.** Loki's CLI still labels the bloom flags
  `Experimental.`. Breaking changes between 3.x minors have already
  happened — e.g. per-tenant `limits_config` key names drift. Any
  future otel-lgtm bump needs a `loki -verify-config` pass.
- **No full-field indexing.** Blooms accelerate substring filters
  but do NOT give us word-level IR (stemming, synonyms, relevance
  scoring). That's the OpenSearch territory we explicitly opt out
  of.

### Neutral

- Storage cost grows slightly — one bloom per chunk per series.
  Size capped at 128 MB / stream by the Loki default. For Mirador's
  volumes that's a rounding error.

## References

- `docker-compose.observability.yml` — image pin + mount
- `infra/observability/loki-override.yaml` — the bloom-enabled config
- [Loki 3.0 release notes — bloom filters](https://grafana.com/docs/loki/latest/release-notes/v3-0/#bloom-filters)
- [Grafana Logs Drilldown (12.4)](https://grafana.com/docs/grafana/latest/explore/logs-integration/)
- [ADR-0006](0006-grafana-duplication.md) — keep observability in one place (Grafana)
- [ADR-0007](0007-retire-prometheus-ui-visualisations.md) — UI pulled out of the metric-visualisation business
- [ADR-0010](0010-otlp-push-to-collector.md) — OTLP push pipeline feeding this stack
