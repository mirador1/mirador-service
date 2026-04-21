# Architecture — Component Reference

> Back to [README](../README.md)

## Component reference

### Security pipeline — applied to every HTTP request, in this order

| # | Component | Role | Interfaces with |
|---|-----------|------|-----------------|
| ① | `RateLimitingFilter` | Token-bucket rate limiter: 100 req/min per source IP. Rejects with HTTP 429 before any business logic runs. Uses Bucket4j with a Redis-backed store so the limit is shared across multiple application instances. | Redis (token bucket state) |
| ② | `IdempotencyFilter` | If the request carries an `Idempotency-Key` header, checks a bounded in-memory + Redis cache. On a cache hit, returns the cached response immediately — the controller never executes. On a miss, executes the request and stores the response. Prevents duplicate `POST /customers` from two parallel network retries. | Redis (idempotency cache) |
| ③ | `JwtAuthenticationFilter` | Validates the `Authorization: Bearer <token>` header. Supports **two token issuers in parallel**: (a) built-in tokens signed by `JwtTokenProvider` (HMAC-SHA256, obtained from `POST /auth/login`); (b) Keycloak tokens validated via JWKS (RSA, fetched once at startup from Keycloak's well-known endpoint). A single filter avoids the `SecurityContext` wipe that occurs when Spring Security's `BearerTokenAuthenticationFilter` runs alongside a custom filter. | Keycloak (JWKS, startup only) |

### Customer domain

| Component | Role | Interfaces with |
|-----------|------|-----------------|
| `CustomerController` | REST layer. Maps HTTP verbs to service calls. Implements header-based API versioning (`X-API-Version: 2.0` → `CustomerDtoV2` with `createdAt`). Delegates all business logic to `CustomerService`. | `CustomerService`, Spring MVC |
| `CustomerService` | Transactional service. Orchestrates persistence (`CustomerRepository`), event publishing, Redis ring buffer update, and enrichment replies. All writes are wrapped in a transaction; Kafka publish happens after commit to avoid phantom events on rollback. | `CustomerRepository` (PostgreSQL), `CustomerEventPublisher` (Kafka), `RecentCustomerBuffer` (Redis) |
| `AggregationService` | Calls two independent sources concurrently using **Java 25 virtual threads** (`Thread.ofVirtual().start(...)`). Intentionally sleeps 200 ms in each sub-task to simulate I/O latency — verifiable in Tempo as two parallel spans finishing at the same wall-clock time. | `BioService` (Ollama), `TodoService` (HTTP) |
| `RecentCustomerBuffer` | Maintains a fixed-size ring buffer of the 10 most recently created customers. Implemented with Redis `LPUSH + LTRIM + LRANGE` so it survives application restarts and is consistent across instances. Exposed via `GET /customers/recent`. | Redis |
| `CustomerStatsScheduler` | Periodic background task (`@Scheduled`). Protected by **ShedLock** via JDBC — only one instance in a cluster acquires the lock and runs the task. Prevents duplicated stats computation when multiple replicas are running. | PostgreSQL (ShedLock table), Redis (stats cache) |

### Messaging (Kafka)

| Component | Role | Topic | Interfaces with |
|-----------|------|-------|-----------------|
| `CustomerEventPublisher` | Fire-and-forget publish. After a successful `POST /customers`, sends a `CustomerCreatedEvent` to `customer.created`. Uses `KafkaTemplate<String, Object>` with Jackson JSON serialization and `__TypeId__` header for dynamic type resolution on the consumer side. | `customer.created` (produce) | Kafka |
| `CustomerEventListener` | `@KafkaListener` on `customer.created`. Logs the event, increments a Micrometer counter (`kafka.customer.created.processed`). Demonstrates decoupled async consumption in the same process — in a real system this would be a separate microservice. | `customer.created` (consume) | Kafka, Micrometer |
| `CustomerEnrichHandler` | Synchronous request-reply. `GET /customers/{id}/enrich` sends a `CustomerEnrichRequest` on `customer.request` via `ReplyingKafkaTemplate` (blocks up to 5 s). This handler listens on `customer.request`, builds a `displayName`, and uses `@SendTo` to publish the reply on `customer.reply`. The template correlates the reply back to the waiting caller via a UUID correlation header. Timeout → HTTP 504 with RFC 9457 Problem Details. | `customer.request` (consume), `customer.reply` (produce) | Kafka |

### Integration (external calls)

| Component | Role | Interfaces with |
|-----------|------|-----------------|
| `BioService` | Calls a local **Ollama** LLM via **Spring AI `ChatClient`**. Wrapped in a **Resilience4j circuit breaker**: after 5 consecutive failures the breaker opens and `BioService` returns a static fallback string immediately, without waiting for Ollama's 30 s timeout. Also wrapped in a Retry (3 attempts with exponential backoff). | Ollama (HTTP, local) |
| `TodoService` | Calls the public `jsonplaceholder.typicode.com` REST API using Spring's **HTTP Interface** (`@HttpExchange`). The interface is bound to a `RestClient` at startup — no Feign or WebClient needed. Also wrapped in Resilience4j circuit breaker + retry. | jsonplaceholder (HTTP, external) |

### Observability (cross-cutting)

| Component | Role | Interfaces with |
|-----------|------|-----------------|
| `RequestIdFilter` | Generates a UUID request ID and stores it in a **Java 25 `ScopedValue`** (structured concurrency). The ID propagates automatically to child virtual threads without `ThreadLocal` leaks. Included in every log line via MDC. | MDC, ScopedValue |
| `TraceService` + OTel | OpenTelemetry auto-instrumentation decorates every HTTP request, JDBC query, and Kafka send/receive with spans. Exported via OTLP to **Tempo** (traces) and **Loki** (structured logs). Every log line carries `traceId` and `spanId` for cross-signal correlation. | Tempo (OTLP gRPC), Loki (OTLP HTTP) |
| `DatabaseReachabilityHealthIndicator` | Custom `HealthIndicator` that issues a `SELECT 1` query (not just a connection ping) against PostgreSQL. Surfaced at `/actuator/health/readiness`. A Kubernetes readiness probe on this endpoint stops traffic routing before users see errors. | PostgreSQL, `/actuator/health` |
| `KafkaHealthIndicator` | Custom `HealthIndicator` that calls `AdminClient.describeCluster()` with a 3s timeout. Included in the readiness group. | Kafka, `/actuator/health` |
| `OllamaHealthIndicator` | Custom `HealthIndicator` that pings `GET /api/tags` on the Ollama server. | Ollama, `/actuator/health` |
| Micrometer + Prometheus | `ObservabilityConfig` registers custom counters and timers. Spring Boot auto-instruments HTTP server requests (histograms for p50/p95/p99), JVM, datasource, and Kafka. Scraped by Prometheus at `/actuator/prometheus` every 15 s. | Prometheus → Grafana `:3000` |

## Call flows

### Flow 1 — `POST /customers` (happy path)

```
① RateLimitingFilter       — check bucket in Redis, decrement
② IdempotencyFilter        — check Idempotency-Key in Redis (miss → proceed)
③ JwtAuthenticationFilter  — validate JWT, populate SecurityContext (ROLE_ADMIN required)
④ CustomerController       — parse CreateCustomerRequest, call CustomerService
⑤ CustomerService          — BEGIN TRANSACTION
   ⑤a CustomerRepository   — INSERT INTO customers → id=1
   ⑤b RecentCustomerBuffer — LPUSH customer:recent id=1 + LTRIM 0 9
   ⑤c COMMIT
   ⑤d CustomerEventPublisher — KafkaTemplate.send("customer.created", CustomerCreatedEvent{id=1})
   ⑤e WebSocket             — SimpMessagingTemplate.convertAndSend("/topic/customers", dto)
⑥ → HTTP 201 {"id":1, "name":"Alice", "email":"alice@example.com"}
   (async, after 201)
⑦ CustomerEventListener    — @KafkaListener on customer.created → logs + counter++
```

### Flow 2 — `GET /customers/{id}/enrich` (Kafka request-reply)

```
① – ③  same filter pipeline
④ CustomerController        — call CustomerService.enrich(id)
⑤ CustomerService           — call ReplyingKafkaTemplate.sendAndReceive("customer.request",
                               CustomerEnrichRequest{id}, timeout=5s)
   ⑤a Kafka broker          — routes message to customer.request
   ⑤b CustomerEnrichHandler — @KafkaListener, builds displayName="Alice <alice@example.com>"
   ⑤c @SendTo               — KafkaTemplate.send("customer.reply", CustomerEnrichReply{displayName})
   ⑤d ReplyingKafkaTemplate — correlates reply via UUID header, unblocks
⑥ → HTTP 200 {"id":1, "displayName":"Alice <alice@example.com>"}
   timeout → HTTP 504  {"type":"urn:problem:kafka-timeout", "status":504}
```

### Flow 3 — `GET /customers/aggregate` (parallel virtual threads)

```
① – ③  same filter pipeline
④ CustomerController  — call AggregationService.aggregate()
⑤ AggregationService  — spawn two virtual threads concurrently:
   ⑤a Thread 1        — BioService → Spring AI ChatClient → POST http://ollama:11434/api/chat
                         (Resilience4j circuit breaker + retry wraps this call)
   ⑤b Thread 2        — TodoService → RestClient → GET https://jsonplaceholder.typicode.com/todos/1
                         (Resilience4j circuit breaker + retry wraps this call)
   both sleep 200 ms to simulate I/O — threads released, not blocked
   both join ~200 ms later (not 400 ms)
⑥ → HTTP 200 {aggregated result}
   p50 ≈ 200 ms, p99 ≈ 220 ms  (visible in Grafana :3000)
   two overlapping child spans visible in Tempo trace
```

### Flow 4 — Keycloak machine-to-machine auth

```
api-gateway service                 Keycloak                    customer-service
      │                                │                               │
      │── POST /realms/customer-service/protocol/openid-connect/token ─►
      │   grant_type=client_credentials                                │
      │   client_id=api-gateway                                        │
      │   client_secret=dev-secret                                     │
      │◄── signed JWT (RSA, 1h TTL, roles: ROLE_ADMIN + ROLE_USER) ───│
      │                                                                 │
      │── GET /customers  Bearer <jwt> ────────────────────────────────►
      │                                         JwtAuthenticationFilter│
      │                                      fetches JWKS (cached)     │
      │                                      verifies RSA signature     │
      │                                      extracts realm_access.roles│
      │◄── 200 OK ──────────────────────────────────────────────────────│
```

## Code organisation

```
com.example.customerservice
├── api/            ApiError, ApiExceptionHandler          — RFC 9457 error responses
├── auth/           JwtTokenProvider, JwtAuthenticationFilter,
│                   SecurityConfig, AuthController,
│                   LoginAttemptService, ApiKeyAuthenticationFilter,
│                   SecurityHeadersFilter                  — JWT auth + Spring Security + OWASP
├── customer/       Customer, CustomerRepository,
│                   CustomerService, CustomerController,
│                   CustomerDto, CustomerDtoV2,            — core domain
│                   AggregationService, RecentCustomerBuffer,
│                   CustomerStatsScheduler, SecurityDemoController,
│                   BatchImportResult, CursorPage
├── integration/    BioService, JsonPlaceholderClient,
│                   TodoService                            — external HTTP calls (HTTP Interface + Spring AI)
├── messaging/      KafkaConfig, CustomerCreatedEvent,
│                   CustomerEnrichHandler,
│                   CustomerEventListener, WebSocketConfig — Kafka + WebSocket
├── observability/  ObservabilityConfig, DatabaseReachabilityHealthIndicator,
│                   KafkaHealthIndicator, OllamaHealthIndicator,
│                   RequestIdFilter, RequestContext,
│                   TraceService,
│                   AuditService                           — health, tracing, metrics, audit
├── resilience/     IdempotencyFilter, RateLimitingFilter,
│                   ShedLockConfig                         — rate limiting, idempotency, distributed lock
└── CustomerServiceApplication.java
```
