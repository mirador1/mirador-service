# Spring Boot 4 – Observable Customer Service

This project has one goal: demonstrate what it takes to diagnose an incident on a backend service.
The stack is built around that scenario — not around the technologies themselves.

---

## Quick start

```bash
# 1. Start infrastructure (PostgreSQL, Kafka, Redis, Grafana, Prometheus, Loki, Tempo)
./run.sh obs

# 2. Get a token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

# 3. Create a customer
curl -s -X POST http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'
# → {"id":1,"name":"Alice","email":"alice@example.com"}

# 4. Trigger synchronous Kafka enrichment
curl -s http://localhost:8080/customers/1/enrich \
  -H "Authorization: Bearer $TOKEN"
# → {"id":1,"name":"Alice","email":"alice@example.com","displayName":"Alice <alice@example.com>"}

# 5. Observe
curl -s http://localhost:8080/actuator/prometheus | grep customer
open http://localhost:3000   # Grafana — HTTP throughput, latency, customer count
open http://localhost:3001   # Grafana + OTel — distributed traces (Tempo), structured logs (Loki)
```

---

## What this demonstrates

### Core — observability and diagnosis

Everything in this section is necessary to answer: *what is slow, what failed, and why?*

| Capability | How it's implemented |
|---|---|
| Distributed tracing | OpenTelemetry → Tempo (OTLP); DB spans via `datasource-micrometer` |
| Metrics and latency histograms | Micrometer → Prometheus → Grafana (p50/p95/p99, custom counters) |
| Structured logs correlated with traces | OTel log exporter → Loki, trace ID injected in every log line |
| Health probes | Custom `DatabaseReachabilityHealthIndicator`, liveness/readiness groups |
| Operational endpoints | `/actuator/health/readiness`, `/actuator/prometheus`, Swagger UI |

### Secondary — additional patterns covered

These patterns are present and documented, but they support the scenario rather than define it.

| Pattern | What it illustrates |
|---|---|
| Kafka fire-and-forget + request-reply | Async decoupling vs sync correlation with built-in timeout |
| JWT + optional Keycloak | Two auth modes in one filter chain without interference |
| Resilience4j circuit breaker + retry | Graceful degradation when an external dependency fails |
| Bucket4j rate limiting | Token-bucket per IP, enforced before business logic |
| ShedLock | Distributed `@Scheduled` lock — prevents duplicate execution across instances |
| Spring AI + Ollama | Local LLM integration with circuit breaker fallback |
| GraalVM native image | AOT trade-offs: ~50 ms start, ~50 MB RSS vs JVM baseline |
| Virtual threads (Project Loom) | Parallel sub-tasks in `AggregationService`, enabled globally |

---

## Diagnostic scenarios

Three scenarios that show the observability stack in action.

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
# http_server_requests_seconds_sum{uri="/customers/aggregate",...} ~20.0
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

The `ReplyingKafkaTemplate` blocks for 5 s then throws — caught by the global exception handler
and mapped to RFC 9457 Problem Details. The timeout metric:
```bash
curl -s http://localhost:8080/actuator/metrics/customer.enrich.duration
```

---

## API reference

### Authentication

All endpoints except `/auth/login` and `/actuator/**` require a Bearer token.

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}'
# → {"token":"eyJhbGci..."}

export TOKEN=<token>
```

### Customer endpoints

```bash
# List all customers
curl -s http://localhost:8080/customers -H "Authorization: Bearer $TOKEN"

# List — API v2 (adds createdAt field)
curl -s http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 2.0"

# Create (ROLE_ADMIN required)
curl -s -X POST http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'

# Idempotent create (same Idempotency-Key returns cached response, no duplicate insert)
curl -s -X POST http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: req-001' \
  -d '{"name":"Alice","email":"alice@example.com"}'

# 10 most recent customers (Redis ring buffer)
curl -s http://localhost:8080/customers/recent -H "Authorization: Bearer $TOKEN"

# Aggregate (200 ms intentional latency — parallel virtual threads)
curl -s http://localhost:8080/customers/aggregate -H "Authorization: Bearer $TOKEN"

# Enrich via Kafka request-reply (blocks up to 5 s)
curl -s http://localhost:8080/customers/1/enrich -H "Authorization: Bearer $TOKEN"
```

### Operational endpoints (no auth)

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/actuator/prometheus | grep 'http_server_requests\|customer'
```

---

## Observability

| Dashboard | URL | Shows |
|-----------|-----|-------|
| Grafana — HTTP | http://localhost:3000 | Throughput, latency (p50/p95/p99), customer creation rate, buffer size |
| Prometheus | http://localhost:9090 | Raw metrics, histogram queries |
| Grafana — OTel | http://localhost:3001 | Distributed traces (Tempo), structured logs (Loki) |

### Trace a request end-to-end

1. `POST /customers` with `Authorization: Bearer $TOKEN`
2. Open http://localhost:3001 → Explore → Tempo
3. Search by service `customer-service`, operation `POST /customers`
4. The trace shows: HTTP handler span → DB insert span → Kafka publish span

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

```bash
curl -s http://localhost:8080/actuator/metrics/kafka.customer.created.processed
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
# Stop Ollama, then call enrich several times — circuit transitions CLOSED → OPEN
docker compose stop ollama
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state \
  --data-urlencode "tag=name:ollama"
```

### Rate limiting

```bash
# 101st request in the same minute → 429
curl -s http://localhost:8080/customers -H "Authorization: Bearer $TOKEN"
```

---

## Running locally

```bash
./run.sh help           # all commands

./run.sh db             # PostgreSQL only
./run.sh kafka          # Kafka (KRaft, no ZooKeeper)
./run.sh obs            # full observability stack (Grafana, Prometheus, Loki, Tempo)
./run.sh all            # everything + the application

./run.sh test           # unit tests (no Docker)
./run.sh integration    # integration tests (Testcontainers — requires Docker)
./run.sh verify         # lint + unit + integration (mirrors CI)
```

Pre-push hook (via lefthook) runs unit tests automatically before every `git push`.

---

## Code organisation

```
com.example.customerservice
├── api/            ApiError, ApiExceptionHandler          — RFC 9457 error responses
├── auth/           JwtTokenProvider, JwtAuthenticationFilter,
│                   SecurityConfig, AuthController         — JWT auth + Spring Security
├── customer/       Customer, CustomerRepository,
│                   CustomerService, CustomerController,
│                   CustomerDto, CustomerDtoV2,            — core domain
│                   AggregationService, RecentCustomerBuffer,
│                   CustomerStatsScheduler
├── integration/    BioService, JsonPlaceholderClient,
│                   TodoService                            — external HTTP calls (HTTP Interface + Spring AI)
├── messaging/      KafkaConfig, CustomerCreatedEvent,
│                   CustomerEnrichHandler,
│                   CustomerEventListener                  — Kafka fire-and-forget + request-reply
├── observability/  ObservabilityConfig, DatabaseReachabilityHealthIndicator,
│                   RequestIdFilter, RequestContext,
│                   TraceService                           — health, tracing, metrics, request ID
├── resilience/     IdempotencyFilter, RateLimitingFilter,
│                   ShedLockConfig                         — rate limiting, idempotency, distributed lock
└── CustomerServiceApplication.java
```

---

## CI/CD

The project runs the same pipeline on two platforms simultaneously.

**Why both?**
GitLab CI is the primary pipeline — it has SAST, dependency scanning, and the scheduled GraalVM
native build. GitHub Actions provides public visibility: anyone browsing the GitHub mirror sees
green checks, test results, and a published Docker image without needing access to the GitLab
instance. The two pipelines are kept intentionally in sync; divergence would defeat the purpose.

| Pipeline | Config | Trigger | Jobs |
|----------|--------|---------|------|
| **GitLab CI** | `.gitlab-ci.yml` | MR push + main push | hadolint → unit tests + SAST + dependency scan → integration tests + SpotBugs + JaCoCo → JAR + Docker image |
| **GitHub Actions** | `.github/workflows/ci.yml` | Push + PR | Same stages — mirrors the GitLab pipeline |

Scheduled (daily, both platforms): GraalVM native image — only when `Dockerfile.native`, `pom.xml` or `src/` changed (5–15 min, skipped otherwise).

```bash
./run.sh verify   # local equivalent of the full CI pipeline
```

---

## Screenshots

### Grafana — HTTP metrics
![Grafana Dashboard](docs/screenshots/grafana-overview.png)

### Prometheus — raw metrics
![Prometheus Dashboard](docs/screenshots/prometheus-overview.png)

### Grafana — OpenTelemetry traces
![Grafana OTel Dashboard](docs/screenshots/grafana-otel-overview.png)

---

## Appendix: Full mechanism list

| Area | Mechanism | Where |
|---|---|---|
| **Language** | Java 21+ virtual threads (`spring.threads.virtual.enabled`) | `application.yml` |
| **Language** | Java 21+ `ScopedValue` for request-ID propagation | `observability/RequestContext`, `RequestIdFilter` |
| **Language** | Java 16+ Records for DTOs | `CustomerDto`, `CreateCustomerRequest`, `EnrichedCustomerDto` |
| **API** | Spring Boot 4 `spring.mvc.apiversion.*` — header-based versioning | `application.yml`, `CustomerController`, `CustomerDtoV2` |
| **API** | RFC 9457 Problem Details | `api/ApiExceptionHandler`, `spring.mvc.problemdetails.enabled` |
| **Security** | JWT Bearer token authentication (JJWT) | `auth/` package |
| **Security** | Spring Security role-based access (`ROLE_ADMIN` / `ROLE_USER`) | `auth/SecurityConfig` |
| **Security** | Keycloak OAuth2 resource server integration | `auth/SecurityConfig`, `docker-compose.yml` |
| **Resilience** | Bucket4j token-bucket rate limiting (per IP, 100 req/min) | `resilience/RateLimitingFilter` |
| **Resilience** | Idempotency header (`Idempotency-Key`) with bounded LRU cache | `resilience/IdempotencyFilter` |
| **Resilience** | Resilience4j circuit breaker + retry on external HTTP calls | `integration/TodoService`, `BioService` |
| **Resilience** | ShedLock distributed scheduler lock (JDBC provider) | `resilience/ShedLockConfig`, `customer/CustomerStatsScheduler` |
| **Messaging** | Kafka fire-and-forget async event publish | `messaging/CustomerEventListener` |
| **Messaging** | Kafka synchronous request-reply (`ReplyingKafkaTemplate`) | `messaging/CustomerEnrichHandler` |
| **Integration** | HTTP Interface (`@HttpExchange`) for external REST calls | `integration/JsonPlaceholderClient` |
| **Integration** | Spring AI ChatClient with Ollama (local LLM) + circuit breaker | `integration/BioService` |
| **Observability** | Micrometer + Prometheus metrics scraping | `observability/ObservabilityConfig` |
| **Observability** | OpenTelemetry trace export to Tempo | `application.yml` (OTLP config) |
| **Observability** | OpenTelemetry structured log export to Loki | `application.yml` (OTLP config) |
| **Observability** | JDBC DataSource instrumentation (datasource-micrometer) | `pom.xml` |
| **Observability** | Custom health indicator + liveness/readiness probes | `observability/DatabaseReachabilityHealthIndicator` |
| **Data** | Flyway schema migrations | `db/migration/V1__*.sql`, `V2__*.sql` |
| **Testing** | Testcontainers (PostgreSQL + Kafka) integration tests | `AbstractIntegrationTest` |
| **Testing** | Spring Boot 4 `RestTestClient` MockMvc DSL | `customer/CustomerRestClientITest` |
| **Testing** | ArchUnit architectural constraint tests | `ArchitectureTest` |
| **Testing** | JaCoCo merged unit + integration coverage gate (≥ 60 %) | `pom.xml` |
| **Build** | SpotBugs static bytecode analysis (Medium threshold) | `pom.xml`, `spotbugs-exclude.xml` |
| **Build** | GraalVM native image support (`-Pnative`) | `pom.xml` native profile, `Dockerfile.native` |
| **Build** | Docker layered JAR optimisation | `Dockerfile` |
