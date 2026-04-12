# Improvement Proposals — Spring Boot 4 Demo

A curated list of enhancements that demonstrate new or noteworthy Java / Spring mechanisms.
Each item is deliberately small enough to fit in a single MR and paired with a concrete
"why this matters for CV / interview" rationale.

---

## Java 25 / JVM

- [ ] **Structured Concurrency (JEP 499)**
  Replace `AggregationService`'s `Executors.newVirtualThreadPerTaskExecutor()` with
  `StructuredTaskScope.ShutdownOnFailure`. This is the official Java 25 API for scoped
  parallel work: child tasks cannot outlive the scope, and the first failure automatically
  cancels siblings. Demonstrates modern error-propagation semantics that compilers and
  linters can verify statically.

- [ ] **Value Objects / Primitive classes (JEP 401 — preview)**
  Migrate `CustomerDto` and `CustomerEnrichRequest` to value classes (`value class Foo {}`).
  Value objects have no identity, enabling JVM flattening — no object header, no pointer
  indirection. Useful for showing awareness of upcoming JVM performance improvements.

- [ ] **Foreign Function & Memory API (JEP 454)**
  Add a toy endpoint that reads a C struct from off-heap memory via `MemorySegment`.
  Demonstrates that Java can now replace JNI for native interop without `sun.misc.Unsafe`.

---

## Spring Boot 4

- [ ] **`@ConditionalOnMissingBean` for pluggable defaults**
  Expose `BioService` as an interface with a default Ollama implementation. A test
  configuration provides a `@MockBean` implementation via `@ConditionalOnMissingBean`.
  Demonstrates the Spring auto-configuration contract that library authors use to ship
  safe defaults without locking consumers in.

- [ ] **Spring Authorization Server (local OAuth2 AS)**
  Replace the hardcoded `admin/admin` credentials with a local Spring Authorization Server
  instance (added to `docker-compose.yml`). Clients use the Client Credentials or
  Authorization Code + PKCE flow. Shows a complete OAuth2 setup without a third-party
  dependency, ideal for demonstrating security architecture in interviews.

- [ ] **`@HttpExchangeAdapter` with retry-aware `RestClient`**
  The current `JsonPlaceholderClient` uses Spring 6's `@HttpExchange`. Swap the underlying
  client from `RestClient` to a `RestClient` decorated with a Resilience4j-aware
  `ClientHttpRequestInterceptor`. Demonstrates that HTTP Interface adapters are
  transport-agnostic and composable with cross-cutting concerns.

---

## Observability

- [ ] **Baggage propagation via OpenTelemetry**
  Propagate `userId` from the JWT claims as an OTel `Baggage` entry. Inject it as a span
  attribute so every trace in Tempo shows which user triggered the request. Demonstrates
  distributed context propagation beyond `traceId`/`spanId`.

- [ ] **Exemplars: link Prometheus metrics to trace IDs**
  Enable OTel exemplar support in Micrometer so that Prometheus histograms
  (`customer.create.duration_bucket`) carry a `{traceId}` label. In Grafana, clicking a
  spike on the latency graph can navigate directly to the guilty trace in Tempo.
  Requires Prometheus ≥ 2.43 and Grafana exemplar data source linking.

- [ ] **`@Timed` on service methods**
  Annotate `CustomerService.create` and `TodoService.getTodos` with
  `@io.micrometer.core.annotation.Timed`. This auto-registers a `Timer` via the
  `TimedAspect` bean, removing the manual `Timer.record()` boilerplate in the controller.
  Good demonstration of AOP-driven instrumentation and the difference between
  programmatic and declarative metrics.

---

## Resilience

- [ ] **Bulkhead (thread-pool isolation) via Resilience4j**
  Add `@Bulkhead(name = "ollama", type = THREADPOOL)` to `BioService.generateBio`. This
  caps the number of concurrent LLM calls, preventing a slow Ollama from exhausting the
  virtual-thread carrier pool under load. Pairs naturally with the existing circuit breaker.

- [ ] **Retry with exponential backoff + jitter on Kafka publish**
  The current `CustomerService` uses a fire-and-forget `KafkaTemplate.send()`. Wrap it in
  a Resilience4j `@Retry` with exponential backoff and jitter. This prevents a retry storm
  if the Kafka broker is temporarily unavailable, demonstrating production-grade messaging
  resilience.

- [ ] **Distributed caching with Redis (replace `RecentCustomerBuffer`)**
  Replace the in-memory `LinkedList` buffer with a Redis `LRANGE` + `LPUSH` + `LTRIM`
  pipeline. Add `spring-boot-starter-data-redis` and a Redis service to
  `docker-compose.yml`. Shows that caching is stateless and survives pod restarts — a key
  distinction from in-process caches in Kubernetes deployments.

---

## Data

- [ ] **Flyway `R__` repeatable migrations for test data**
  Add `src/main/resources/db/migration/R__seed_demo_customers.sql` containing `MERGE`/`INSERT … ON CONFLICT DO NOTHING` statements. Repeatable migrations re-run whenever their checksum changes, making local dev teardown + reseed trivial. Shows awareness of the full Flyway migration lifecycle.

- [ ] **Spring Data Specifications for dynamic filtering**
  Add `GET /customers?name=&email=` query parameters backed by a `JpaSpecificationExecutor`
  + `Specification<Customer>` builder. Demonstrates the Repository pattern's extension
  points for dynamic queries without raw JPQL strings.

- [ ] **Spring Data Projections**
  Introduce a `CustomerSummary` interface projection (just `id` + `name`) returned by
  `GET /customers/summary`. Spring Data fetches only the projected columns from the DB —
  no `SELECT *` anti-pattern. Shows knowledge of JPA query optimisation.

---

## Async / Reactive

- [ ] **WebFlux endpoint alongside WebMVC**
  Add a `RouterFunction` bean that exposes `GET /stream/customers` as a Server-Sent Events
  stream via `Flux<CustomerDto>`. Spring Boot 4 supports mixed WebMVC + WebFlux
  deployments. Demonstrates coexistence of imperative and reactive paradigms without a full
  migration.

- [ ] **`@Async` with virtual-thread executor**
  Annotate `CustomerService.notifyExternalSystem` (new stub method) with `@Async`.
  Configure `spring.task.execution.pool.virtual-threads=true` (Spring Boot 4 property) to
  run async tasks on virtual threads instead of platform threads. Shows the Spring Boot 4
  virtual-thread executor integration.

- [ ] **Spring Integration for topic routing**
  Add a Spring Integration flow that consumes `customer.created` events and routes them to
  different processing pipelines based on email domain (e.g., corporate vs. personal).
  Demonstrates that Spring Kafka and Spring Integration can coexist and that Integration
  handles routing/transformation without custom consumer code.

---

## Security

- [ ] **Spring Security method-level authorization (`@PreAuthorize`)**
  Add `ROLE_ADMIN` checks at the service level using `@PreAuthorize("hasRole('ADMIN')")` on
  `CustomerService.create` and `deleteById`. Method security is more defensible than
  controller-level rules because it is enforced even for internal (non-HTTP) callers. Pairs
  with the Keycloak OAuth2 integration (MR 22).

- [ ] **API key authentication alongside JWT**
  Add a second `OncePerRequestFilter` that accepts `X-Api-Key` header for machine-to-machine
  callers (e.g., CI pipelines). The filter populates the `SecurityContext` with a
  `ROLE_MACHINE` authority. Demonstrates multiple coexistent authentication schemes, a
  common real-world requirement.

- [ ] **JWT refresh token flow**
  Add `POST /auth/refresh` that accepts a long-lived refresh token stored server-side in
  Redis and returns a new short-lived access token. The current 24 h access token expiry is
  too long for production; a 15-minute access + 7-day refresh design is the industry
  standard. Shows the complete auth lifecycle beyond initial login.

---

## Testing

- [ ] **Contract testing with Spring Cloud Contract (producer side)**
  Add a `contracts/` directory with Groovy DSL contracts for `POST /customers` and
  `GET /customers/{id}`. `spring-cloud-contract-maven-plugin` generates JUnit 5 tests from
  the contracts and a WireMock stub JAR for consumer teams. Demonstrates the shift-left
  approach to API compatibility testing.

- [ ] **Mutation testing with PIT (`pitest-maven-plugin`)**
  Add PIT to the build. Mutation testing injects small code mutations (e.g., flip a `>`
  to `>=`) and checks whether the test suite catches them. A high mutation score (>70%)
  is a better quality signal than line coverage alone.

- [ ] **Chaos engineering with Toxiproxy in integration tests**
  Replace `AbstractIntegrationTest`'s direct container connections with Toxiproxy proxies.
  Add tests that simulate network latency, packet loss, and connection drops between the app
  and PostgreSQL / Kafka. Demonstrates that the resilience4j + retry configuration actually
  protects against real failure modes, not just simulated exceptions in unit tests.

---

## Architecture / Cross-cutting

- [ ] **Outbox pattern for reliable Kafka publishing**
  Instead of calling `KafkaTemplate.send()` inside the same DB transaction as
  `customerRepository.save()`, write a `CustomerOutboxEvent` row to a DB table inside the
  same transaction. A separate `@Scheduled` job (protected by ShedLock) polls the outbox
  and publishes pending events. Guarantees exactly-once semantics between DB and Kafka
  without distributed transactions (XA).

- [ ] **Correlation ID propagation across Kafka messages**
  Inject the `X-Request-Id` from `RequestIdFilter` as a Kafka message header via a
  `ProducerInterceptor`. A `ConsumerInterceptor` on the listener side extracts it and
  restores the MDC. This creates end-to-end log correlation across async message boundaries.
