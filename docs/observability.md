# Observability

> Back to [README](../README.md)

## Table of contents

- [Dashboards](#dashboards)
- [Trace a request end-to-end](#trace-a-request-end-to-end)
- [Diagnostic scenarios](#diagnostic-scenarios)
- [Kafka patterns](#kafka-patterns)
- [Resilience](#resilience)

---

## Dashboards

| Dashboard | URL | Shows |
|-----------|-----|-------|
| Grafana — HTTP | http://localhost:3000 | Throughput, latency (p50/p95/p99), customer creation rate, buffer size |
| Prometheus | http://localhost:9090 | Raw metrics, histogram queries |
| Grafana — OTel | http://localhost:3001 | Distributed traces (Tempo), structured logs (Loki) |
| Zipkin | http://localhost:9411 | Distributed traces — lightweight alternative to Tempo |
| Pyroscope | http://localhost:4040 | Continuous profiling — CPU/memory flamegraphs |
| pgAdmin | http://localhost:5050 | PostgreSQL web admin (admin@demo.com / admin) |
| Kafka UI | http://localhost:9080 | Topics, messages, consumer groups, lag |
| RedisInsight | http://localhost:5540 | Redis key browser, CLI, memory analysis |
| Keycloak | http://localhost:9090 | OAuth2 identity provider admin console (admin / admin) |

## Trace a request end-to-end

1. `POST /customers` with `Authorization: Bearer $TOKEN`
2. Open http://localhost:3001 → Explore → Tempo
3. Search by service `customer-service`, operation `POST /customers`
4. The trace shows: HTTP handler span → DB insert span → Kafka publish span

---

## Diagnostic scenarios

### Scenario 1 — PostgreSQL unavailability

```bash
docker compose stop db
curl -s http://localhost:8080/actuator/health/readiness | jq .
```

Expected response:
```json
{
  "status": "OUT_OF_SERVICE",
  "components": {
    "db": {"status": "DOWN"},
    "dbReachability": {"status": "DOWN", "details": {"error": "Connection refused"}}
  }
}
```

The `db` check is standard Spring Boot. `dbReachability` is a custom `HealthIndicator`
(`observability/DatabaseReachabilityHealthIndicator`) that issues an actual test query — not just
a connection ping. A Kubernetes readiness probe on this endpoint stops traffic routing before
users see errors.

### Scenario 2 — Endpoint latency on `/customers/aggregate`

```bash
for i in {1..100}; do
  curl -s http://localhost:8080/customers/aggregate \
    -H "Authorization: Bearer $TOKEN" > /dev/null
done
```

Expected in Grafana (http://localhost:3000):
- p50 ≈ **200 ms** — two parallel virtual-thread tasks (not 400 ms sequential)
- p99 ≈ **220–250 ms** — low tail latency, no thread pool contention

In Tempo traces: the `loadCustomerData` and `loadStats` sub-spans start and end at the same time,
confirming that virtual-thread parallelism works and the latency is bounded.

```bash
# Raw metric
curl -s http://localhost:8080/actuator/prometheus \
  | grep 'http_server_requests_seconds.*aggregate'
```

### Scenario 3 — Kafka enrichment timeout

```bash
docker compose stop kafka
curl -s http://localhost:8080/customers/1/enrich \
  -H "Authorization: Bearer $TOKEN"
```

Expected after 5 s:
```json
{"type":"urn:problem:kafka-timeout","title":"Kafka Reply Timeout","status":504}
```

### Scenario 4 — Slow query detection

```bash
curl -s "http://localhost:8080/customers/slow-query?seconds=3" \
  -H "Authorization: Bearer $TOKEN"
```

The 3-second DB span is visible in Tempo/Zipkin traces and creates a latency spike in Grafana.

---

## Kafka patterns

### Pattern 1 — Asynchronous (fire-and-forget)

`POST /customers` persists the customer then publishes a `CustomerCreatedEvent` on `customer.created`
without waiting for acknowledgement. A `@KafkaListener` in the same app consumes the event and logs it.

```
POST /customers → CustomerService → KafkaTemplate.send("customer.created") → 201 Created
                                              ↓ (async, decoupled)
                                    CustomerEventListener → logs: kafka_event type=CustomerCreatedEvent
```

### Pattern 2 — Synchronous (request-reply)

`GET /customers/{id}/enrich` sends a request to `customer.request` and blocks until the reply
arrives on `customer.reply` (timeout: 5 s). `ReplyingKafkaTemplate` handles correlation automatically.

```
GET /customers/{id}/enrich
  → ReplyingKafkaTemplate.sendAndReceive("customer.request")  [blocks, max 5 s]
      ↓
  CustomerEnrichHandler [@KafkaListener + @SendTo] → reply on "customer.reply"
      ↓
  → {"displayName":"Alice <alice@example.com>"}
```

---

## Resilience

### Circuit breaker on external calls

`BioService` calls Ollama (local LLM). If Ollama is down, the circuit breaker opens after 5 failures
and returns a degraded response immediately — no 30 s timeout chain.

```bash
docker compose stop ollama
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state \
  --data-urlencode "tag=name:ollama"
```

### Rate limiting

```bash
# 101st request in the same minute → 429
curl -s http://localhost:8080/customers -H "Authorization: Bearer $TOKEN"
```
