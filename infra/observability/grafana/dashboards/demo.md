# demo.json — Grafana dashboard (Prometheus)

Loaded automatically by Grafana via the `dashboard-demo-lgtm.yaml` provisioning file.
Datasource: **Prometheus** (scrapes `/actuator/prometheus`).

Use `./run.sh obs` to start the full observability stack, then open http://localhost:3000.

---

## Panels

| Panel | Query | What it shows |
|---|---|---|
| HTTP requests rate | `sum(rate(http_server_requests_seconds_count[1m]))` | Total request throughput across all endpoints, aggregated per minute |
| Customer created count | `customer_created_count_total` | Cumulative counter incremented by `CustomerService` on each successful `POST /customers` |
| Customer create latency p95 | `histogram_quantile(0.95, sum(rate(customer_create_duration_seconds_bucket[5m])) by (le))` | 95th-percentile latency for the customer creation path (DB write + Kafka publish) |
| Customer aggregate latency p95 | `histogram_quantile(0.95, sum(rate(customer_aggregate_duration_seconds_bucket[5m])) by (le))` | 95th-percentile latency for `GET /customers/{id}/enrich` (parallel sub-tasks via virtual threads) |
| Recent customer buffer size | `customer_recent_buffer_size` | Current size of the in-memory `RecentCustomerBuffer` — useful for spotting unexpected growth |

---

## Metrics origin

| Metric | Registered by |
|---|---|
| `http_server_requests_seconds_*` | Spring Boot auto-configuration (Micrometer + Tomcat instrumentation) |
| `customer_created_count_total` | `CustomerService` — `Counter` registered in `ObservabilityConfig` |
| `customer_create_duration_seconds_*` | `CustomerService` — `Timer` registered in `ObservabilityConfig` |
| `customer_aggregate_duration_seconds_*` | `AggregationService` — `Timer` registered in `ObservabilityConfig` |
| `customer_recent_buffer_size` | `RecentCustomerBuffer` — `Gauge` registered in `ObservabilityConfig` |

---

## Difference with `dashboards-lgtm/demo.json`

The `dashboards-lgtm/` variant uses `_milliseconds_` metric names instead of `_seconds_`.
This reflects the OTel SDK naming convention when metrics are exported via OTLP to the LGTM stack
(Grafana + Loki + Tempo + Mimir/Prometheus), as opposed to the Micrometer → Prometheus direct scrape.
