# Alert: `MiradorHighLatencyP95`

Fired when `mirador:http_request_duration:p95_5m > 1` for ≥ 10 min — the
95th-percentile request takes more than 1 second. Warning-tier (not a
pager): most high-latency episodes resolve themselves as traffic shifts.

## Quick triage (60 seconds)

Latency alerts are almost always localised to ONE endpoint. Don't chase
a global average — find the hot URI first.

```bash
# Top 5 slowest URIs right now:
curl -s 'http://localhost:9090/api/v1/query' --data-urlencode 'query=
  topk(5, histogram_quantile(0.95, sum by (le, uri) (
    rate(http_server_requests_seconds_bucket{job=~"mirador.*"}[5m])
  )))
' | jq '.data.result[] | {uri: .metric.uri, p95: .value[1]}'
```

## Likely root causes

1. **Hot URI is `/customers/aggregate`** — this endpoint sleeps 200 ms by
   DESIGN (AggregationService demos virtual threads). If it dominates
   traffic during a demo, the alert is an artefact, not an outage.
   Silence the alert with a 30-min window during live demos.
2. **Hot URI is `/customers/{id}/bio`** — Ollama LLM call, ~1–5 s
   depending on model + hardware. Expected slow under load; the circuit
   breaker opens past 10 calls in progress.
3. **DB slow query** — check `pg_stat_statements` for a query whose
   `mean_exec_time` just spiked. JOIN on a newly-imported customer set
   may lack an index (Flyway migration recently added).
4. **Kafka enrich timeout** — the `/enrich` endpoint awaits a reply on a
   Kafka topic for up to 5 s. If the consumer is slow, this latency
   shows up. Jump to `kafka-enrich-timeout.md` if that's the hot URI.

## Commands to run

```bash
# Trace a slow request (via Tempo Search in Grafana Explore, or TraceQL):
# {service.name="mirador" && duration>2s}

# If the slow URI is a DB-backed one:
kubectl -n infra exec -it postgresql-0 -- psql -U demo -d customer-service \
  -c "SELECT query, mean_exec_time, calls FROM pg_stat_statements
      ORDER BY mean_exec_time DESC LIMIT 10;"

# Virtual-thread carrier thread contention?
# Pyroscope → lock profile on "mirador" → look for elevated BLOCKED time.
```

## Fix that worked last time

- Missing index on `customer.email` under bulk import → added
  CREATE INDEX CONCURRENTLY in a new Flyway migration.
- Ollama saturated by too many concurrent `/bio` calls → lowered
  Resilience4j bulkhead maxConcurrentCalls from 10 → 5.

## When to escalate

- p95 > 5 s sustained for 20 min → capacity issue, not a single-query
  bug. Consider scaling `deployment/mirador` or raising DB connection
  pool.
- Latency spike correlates with node CPU > 90 % on the pod's node →
  move the workload off a cohabited node in GKE Autopilot (won't
  happen for Mirador since Autopilot owns packing).
