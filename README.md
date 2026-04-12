# Spring Boot 4 – Observable Customer Service

This project demonstrates how to build, operate, and diagnose a production-grade Spring Boot service.
The central scenario: a customer enrichment endpoint slows down under load. The stack exists to make
that situation detectable, traceable, and explainable — not to list technologies.

Each component has a specific role. None was added for its own sake.

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

## Architecture decisions

Every component in this project exists to enable a specific capability. The table below answers
"why is this here?" for each one.

| Component | Why it's here |
|-----------|---------------|
| **Kafka** | Two messaging patterns in one app: fire-and-forget (customer creation event) and synchronous request-reply (enrichment with correlation and timeout). These are the two most common Kafka use cases in microservices. |
| **OpenTelemetry** | Exports traces to Tempo and structured logs to Loki. Enables end-to-end request tracing including DB spans — essential for diagnosing latency that isn't visible in application logs. |
| **Micrometer + Prometheus** | Exposes HTTP latency histograms (p95/p99), custom counters (customer.created, enrich.duration), and Kafka consumer metrics. Grafana dashboards are built on these. |
| **Resilience4j** | Circuit breaker + retry on external HTTP calls (`BioService`, `JsonPlaceholderClient`). Demonstrates graceful degradation: the main API stays responsive when a downstream dependency fails. |
| **Keycloak** | Optional OAuth2 resource server alongside the built-in JWT. Shows how to support two authentication modes in the same filter chain without interference. |
| **Bucket4j rate limiting** | Token-bucket per IP address (100 req/min). Defense before business logic — the filter rejects at the edge, keeping the rest of the stack clean. |
| **ShedLock** | JDBC-backed distributed lock for `@Scheduled` tasks. Prevents duplicate execution when the service runs as multiple instances (Kubernetes, Docker Swarm). |
| **GraalVM native image** | Demonstrates AOT compilation trade-offs: ~50 ms startup and ~50 MB RSS vs ~5 s startup and ~250 MB RSS for the JVM image. Built in the daily scheduled CI pipeline (`-Pnative` profile). |
| **Virtual threads (Project Loom)** | `spring.threads.virtual.enabled=true` — Tomcat, `@Async`, and Kafka use virtual threads. The `AggregationService` spawns two virtual threads per request for parallel sub-tasks, cutting latency from 400 ms to 200 ms. |
| **Redis** | `RecentCustomerBuffer` stores the 10 most recent customers using `LPUSH + LTRIM`. Demonstrates Redis as a bounded ring buffer, not just a cache. |
| **ArchUnit** | Enforces layer isolation at build time: controllers may not call repositories directly, no circular dependencies. Prevents architectural drift before it reaches production. |

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
./run.sh ci             # alias for verify
```

Pre-push hook (via lefthook) runs `./run.sh check` (unit tests) automatically before every `git push`.

---

## API reference

### Authentication

All endpoints except `/auth/login` and `/actuator/**` require a Bearer token.

```bash
# Get a token (valid 24 h)
curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}'
# → {"token":"eyJhbGci..."}

export TOKEN=<token>
```

### Customer endpoints

```bash
# List all customers
curl -s http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN"
# → [{"id":1,"name":"Alice","email":"alice@example.com"}, ...]

# List with API versioning (v2 adds a createdAt field)
curl -s http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-API-Version: 2.0"
# → [{"id":1,"name":"Alice","email":"alice@example.com","createdAt":"2024-01-15T10:30:00"}, ...]

# Create a customer (requires ROLE_ADMIN — included in all built-in tokens)
curl -s -X POST http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'
# → {"id":1,"name":"Alice","email":"alice@example.com"}

# Idempotent creation (same Idempotency-Key returns cached response)
curl -s -X POST http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: req-001' \
  -d '{"name":"Alice","email":"alice@example.com"}'
# Second call with same key → same 200 response, no duplicate insert

# Get 10 most recent customers (from Redis ring buffer)
curl -s http://localhost:8080/customers/recent \
  -H "Authorization: Bearer $TOKEN"
# → [{"id":1,...}, ...]

# Aggregate endpoint (200 ms intentional latency — two parallel virtual-thread tasks)
curl -s http://localhost:8080/customers/aggregate \
  -H "Authorization: Bearer $TOKEN"
# → {"customerData":"customer-data","stats":"stats"}

# Enrich via Kafka request-reply (blocks up to 5 s for Kafka reply)
curl -s http://localhost:8080/customers/1/enrich \
  -H "Authorization: Bearer $TOKEN"
# → {"id":1,"name":"Alice","email":"alice@example.com","displayName":"Alice <alice@example.com>"}
```

### Operational endpoints (no auth)

```bash
curl -s http://localhost:8080/actuator/health
# → {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"},...}}

curl -s http://localhost:8080/actuator/health/readiness
# → {"status":"UP","components":{"db":{"status":"UP"},"dbReachability":{"status":"UP"},...}}

curl -s http://localhost:8080/actuator/prometheus | grep 'http_server_requests\|customer'
# → http_server_requests_seconds_* histograms, kafka.customer.created.processed, ...
```

---

## Observability

### Dashboards

| Dashboard | URL | Shows |
|-----------|-----|-------|
| Grafana — HTTP | http://localhost:3000 | Throughput, latency (p50/p95/p99), customer creation rate, buffer size |
| Prometheus | http://localhost:9090 | Raw metrics, histogram queries |
| Grafana — OTel | http://localhost:3001 | Distributed traces (Tempo), structured logs (Loki) |

### What to look for on `/customers/aggregate`

The endpoint runs two 200 ms sub-tasks in parallel using virtual threads. In Grafana:
- p50 latency stabilises around **200 ms** (not 400 ms — parallelism working)
- In Tempo traces, both sub-spans start at the same time and complete together

### Trace a request end-to-end

1. `POST /customers` with `Authorization: Bearer $TOKEN`
2. Open Grafana OTel → Explore → Tempo
3. Search by service `spring-4-demo`, operation `POST /customers`
4. The trace shows: HTTP handler span → DB insert span (datasource-micrometer) → Kafka publish span

---

## Kafka patterns

This application is both producer and consumer of its own messages, demonstrating two distinct patterns.

### Pattern 1 — Asynchronous (fire-and-forget)

`POST /customers` persists the customer then publishes a `CustomerCreatedEvent` on `customer.created`
**without waiting** for any consumer acknowledgement. A `@KafkaListener` in the same app consumes
the event and logs it.

```
POST /customers → CustomerService → KafkaTemplate.send("customer.created") → 201 Created
                                              ↓ (async, decoupled)
                                    CustomerEventListener.onCustomerCreated()
                                    → logs: kafka_event type=CustomerCreatedEvent id=1 name=Alice
```

```bash
# Create a customer and watch logs
curl -s -X POST http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'

# Verify the event was processed
curl -s http://localhost:8080/actuator/metrics/kafka.customer.created.processed
```

### Pattern 2 — Synchronous (request-reply)

`GET /customers/{id}/enrich` sends a request to `customer.request` and **blocks until the reply**
arrives on `customer.reply` (timeout: 5 s). Correlation is managed automatically by
`ReplyingKafkaTemplate`.

```
GET /customers/{id}/enrich
  → ReplyingKafkaTemplate.sendAndReceive("customer.request")  ← blocks here (max 5 s)
      ↓
  CustomerEnrichHandler.handleEnrichRequest()  [@KafkaListener("customer.request") + @SendTo]
      ↓ computes displayName = "Alice <alice@example.com>"
  → reply published on "customer.reply"
  → ReplyingKafkaTemplate receives correlated reply
  → 200 {"displayName":"Alice <alice@example.com>"}
```

If Kafka is down or the handler does not reply within 5 s:
```bash
curl -s http://localhost:8080/customers/1/enrich -H "Authorization: Bearer $TOKEN"
# → 504 {"type":"urn:problem:kafka-timeout","title":"Kafka Reply Timeout","status":504}
```

```bash
# Duration metric for the enrich round-trip
curl -s http://localhost:8080/actuator/metrics/customer.enrich.duration
```

---

## Resilience

### Circuit breaker on external calls

`GET /customers/{id}/enrich` also calls an external BioService (Ollama LLM). If the LLM is
unavailable, the circuit breaker opens after 5 failures and returns a degraded response immediately
(no waiting 30 s for timeouts to stack up).

```bash
# With Ollama running
curl -s http://localhost:8080/customers/1/enrich -H "Authorization: Bearer $TOKEN"
# → full enriched response with bio

# With Ollama stopped (docker compose stop ollama)
# First 5 calls: 500 (circuit CLOSED, trying each time)
# Subsequent calls: fast 503 (circuit OPEN, no attempt)
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state \
  --data-urlencode "tag=name:ollama"
```

### Rate limiting

```bash
# 101st request in the same minute from the same IP
curl -s http://localhost:8080/customers -H "Authorization: Bearer $TOKEN"
# → 429 Too Many Requests
```

### Diagnostic scenario 1 — PostgreSQL unavailability

Stop PostgreSQL while the application is running:
```bash
docker compose stop db
```

Readiness probe detects it immediately:
```bash
curl -s http://localhost:8080/actuator/health/readiness | jq .
# {
#   "status": "OUT_OF_SERVICE",
#   "components": {
#     "db": {"status": "DOWN"},
#     "dbReachability": {"status": "DOWN", "details": {"error": "Connection refused"}}
#   }
# }
```

A Kubernetes liveness/readiness probe on this endpoint would stop routing traffic to the pod
before users see errors. The `db` check is standard Spring Boot; `dbReachability` is a custom
`HealthIndicator` that issues an actual test query (not just a connection ping).

### Diagnostic scenario 2 — Latency on `/customers/aggregate`

Generate load and observe in Grafana:
```bash
for i in {1..100}; do
  curl -s http://localhost:8080/customers/aggregate \
    -H "Authorization: Bearer $TOKEN" > /dev/null
done
```

Expected in Grafana (http://localhost:3000):
- p50 ≈ **200 ms** — parallel virtual threads working correctly
- p99 ≈ **220–250 ms** — low tail latency (virtual threads, no thread pool contention)

Raw metric:
```bash
curl -s http://localhost:8080/actuator/prometheus \
  | grep 'http_server_requests_seconds.*aggregate'
# http_server_requests_seconds_sum{uri="/customers/aggregate",...} ~20.0 (100 req × 200 ms)
```

In Tempo traces: the two sub-spans (`loadCustomerData`, `loadStats`) are concurrent — they start
and end at the same time, confirming virtual-thread parallelism.

---

## Code organisation

```
com.example.springapi
├── api/            ApiError, ApiExceptionHandler          — RFC 9457 error responses
├── auth/           JwtTokenProvider, JwtAuthenticationFilter,
│                   SecurityConfig, AuthController         — JWT auth + Spring Security
├── customer/       Customer, CustomerRepository,
│                   CustomerService, CustomerController,
│                   CustomerDto, CustomerDtoV2,            — core domain (model, persistence,
│                   CreateCustomerRequest, EnrichedCustomerDto,  service, API versioning)
│                   AggregationService, RecentCustomerBuffer,
│                   CustomerStatsScheduler
├── integration/    BioService, HttpClientConfig,
│                   JsonPlaceholderClient, TodoService,
│                   TodoItem                               — external HTTP calls (HTTP Interface + Spring AI)
├── messaging/      KafkaConfig, CustomerCreatedEvent,
│                   CustomerEnrichRequest, CustomerEnrichReply,
│                   CustomerEnrichHandler, CustomerEventListener — Kafka fire-and-forget + request-reply
├── observability/  ObservabilityConfig, DatabaseReachabilityHealthIndicator,
│                   RequestIdFilter, RequestContext,
│                   TraceService                           — health, tracing, metrics, request ID
├── resilience/     IdempotencyFilter, RateLimitingFilter,
│                   ShedLockConfig                         — rate limiting, idempotency, distributed lock
└── SpringApiApplication.java                             — entry point
```

Test tree mirrors the same hierarchy:

```
src/test/java/com/example/springapi
├── AbstractIntegrationTest.java   — shared Testcontainers base (PostgreSQL + Kafka)
├── ArchitectureTest.java          — ArchUnit architectural constraints
├── SpringApiApplicationITest.java — smoke test (context loads)
├── config/TestAiConfig.java       — mock Spring AI bean for integration tests
├── auth/       AuthITest, JwtTokenProviderTest
├── customer/   CustomerApiITest, CustomerRestClientITest,
│               AggregationServiceTest, RecentCustomerBufferTest
├── messaging/  KafkaPatternITest
└── resilience/ IdempotencyITest, RateLimitingITest
```

---

## CI/CD

Two pipelines run in parallel:

| Pipeline | Trigger | Jobs |
|----------|---------|------|
| **GitLab CI** | MR push + main push | hadolint → unit tests + SAST → integration tests + SpotBugs + JaCoCo → JAR + Docker image |
| **GitHub Actions** | Push + PR | Same stages — mirrors the GitLab pipeline |

Scheduled (daily, GitLab only): GraalVM native image build — only when `Dockerfile.native`, `pom.xml`,
or `src/` changed (5–15 min, skipped otherwise).

Local equivalent:
```bash
./run.sh verify   # lint + unit + integration — mirrors the CI pipeline
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

## Appendix: Mechanisms reference

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
