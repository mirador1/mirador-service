# Technology glossary — mirador-service

This file is a reference catalogue of every technology the backend (`mirador-service`) actually uses,
plus a handful of well-known alternatives we intentionally rejected. Each entry is structured with
three fields so it can be skimmed quickly:

- **What it is** — a one-sentence plain-language definition.
- **Usage here** — how this repo concretely uses it, with file paths when relevant.
- **Why it's pertinent** — why we picked it over alternatives, or why the problem it solves matters to us.

Entries tagged `(rejected)` are tools we looked at and deliberately did NOT adopt. They exist in the
glossary so the next person (or the next Claude session) doesn't waste time re-evaluating them.

Sibling catalogue for the Angular UI: <https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/technologies.md>.

## Table of contents

- [Languages and runtimes](#languages-and-runtimes)
- [Core frameworks](#core-frameworks)
- [Persistence and data](#persistence-and-data)
- [Caching](#caching)
- [Messaging and real-time](#messaging-and-real-time)
- [Authentication and authorization](#authentication-and-authorization)
- [Resilience and rate limiting](#resilience-and-rate-limiting)
- [Observability](#observability)
- [AI and inference](#ai-and-inference)
- [Testing](#testing)
- [Quality analysis](#quality-analysis)
- [Documentation](#documentation)
- [Build and dependencies](#build-and-dependencies)
- [Version control and commits](#version-control-and-commits)
- [CI/CD](#cicd)
- [Local dev environment](#local-dev-environment)
- [Containers and images](#containers-and-images)
- [Kubernetes and orchestration](#kubernetes-and-orchestration)
- [Supply chain security](#supply-chain-security)
- [Cloud providers and platforms](#cloud-providers-and-platforms)
- [Networking](#networking)
- [Cross-reference](#cross-reference)

---

## Languages and runtimes

### ☕ [Java 25](https://www.oracle.com/java/technologies/javase/25-relnote-issues.html)
- **What it is**: The current long-term (LTS) release of the Java platform (GA September 2025).
- **Usage here**: `<java.version>25</java.version>` in `pom.xml`; the default build profile targets class-file major version 69. Virtual threads, ScopedValue, pattern matching with guards (`case X e when ...`) and record patterns are used in `com.mirador.*`.
- **Why it's pertinent**: Virtual threads let the app serve thousands of concurrent HTTP + Kafka consumers with a small thread pool — a perfect fit for a Spring Boot service that spends most of its time waiting on DB, Kafka, Redis and HTTP. Running on LTS avoids the feature-release treadmill.

### ☕ [Java 21](https://www.oracle.com/java/technologies/javase/21-relnote-issues.html) (compat)
- **What it is**: Previous LTS Java release.
- **Usage here**: `-Dcompat` profile (`mvn verify -Dcompat`) downgrades to 21 and copies `src/main/java-overlays/pre-java25` over main sources (`ScopedValue` rewritten as `ThreadLocal`). Also used by the `compat-sb4-java21` and `compat-sb3-java21` CI jobs.
- **Why it's pertinent**: Some customers still run on Java 21. Keeping a green build on 21 proves the codebase doesn't accidentally depend on Java 25-only syntax outside the overlay.

### ☕ [Java 17](https://www.oracle.com/java/technologies/javase/17-relnote-issues.html) (compat)
- **What it is**: Older LTS Java release still widely deployed.
- **Usage here**: `-Djava17` activation property triggers an additional overlay `src/main/java-overlays/java17` that rewrites `switch` pattern matching as `if/else`.
- **Why it's pertinent**: Documented baseline for conservative enterprise environments. CI runs `compat-sb4-java17` and `compat-sb3-java17` as manual jobs so we catch regressions before they reach a customer running Java 17.

### ☕ [Java 26](https://openjdk.org/projects/jdk/26/) (forward-looking)
- **What it is**: Next short-term Java release (GA March 2026).
- **Usage here**: `java26` Maven profile; `mvn verify -Djava26` runs the full build on a Java 26 JDK when available.
- **Why it's pertinent**: Early-warning pipeline — catches byte-code regressions in SpotBugs/ASM before Java 26 becomes the new LTS baseline.

### ☕ [GraalVM Community Edition 25](https://www.graalvm.org/)
- **What it is**: Alternative JVM implementation with an ahead-of-time (AOT) native-image compiler.
- **Usage here**: `Dockerfile.native` stage 1 uses `ghcr.io/graalvm/native-image-community:25`; `mvn -Pnative native:compile` runs `process-aot` then `native:compile`. CI job `build-native` produces the native image on the daily schedule or on demand.
- **Why it's pertinent**: Startup drops from ~3 s to ~50 ms and RSS from ~250 MB to ~50 MB. Critical for serverless (Cloud Run) and GKE Autopilot where pod-level memory costs money.

### ☕ [JVM bytecode](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html)
- **What it is**: The intermediate instruction set emitted by `javac`, versioned per Java release (69 = Java 25).
- **Usage here**: Every library that parses bytecode (ASM, SpotBugs, PMD, ArchUnit) must support version 69 — documented in `pom.xml` comments with upgrade pins.
- **Why it's pertinent**: Version 69 is why we pin ASM 9.8, SpotBugs 4.8.6+, PMD 7.20+, Checkstyle 10.26+ — older versions crash when encountering Java 25 class files.

### ☕ [OpenJDK / Eclipse Temurin](https://adoptium.net/)
- **What it is**: Free, production-grade builds of the OpenJDK project, maintained by the Adoptium working group.
- **Usage here**: `eclipse-temurin:25-jdk` for the builder stage and `eclipse-temurin:25-jre` for the runtime stage in `Dockerfile`. CI uses `maven:3.9.14-eclipse-temurin-25-noble` as the default image.
- **Why it's pertinent**: Permissively licensed, TCK-certified, and the de-facto default in container images. Oracle JDK's licensing is hostile to long-running containers; Temurin just works.

### 🧩 [Lombok](https://projectlombok.org/)
- **What it is**: Annotation processor that generates boilerplate (getters, setters, builders, constructors) at compile time.
- **Usage here**: `provided` scope dependency and explicit `annotationProcessorPaths` entry in `maven-compiler-plugin`. Used on DTOs and entities in `com.mirador.customer`, `com.mirador.auth`, etc.
- **Why it's pertinent**: Eliminates ~30 % of boilerplate without adding runtime footprint. The explicit processor-path entry is required on Java 25 (implicit discovery was tightened).

### 📦 [Maven Central](https://central.sonatype.com/)
- **What it is**: The canonical artifact repository for Java libraries (`repo1.maven.org/maven2`).
- **Usage here**: Primary `<repository>` in `pom.xml`. All production dependencies resolve here.
- **Why it's pertinent**: Immutable, globally CDN-distributed, and trust-anchored via Sonatype. Every other Maven repo is a fallback.

### 🌱 [Spring Milestones repository](https://repo.spring.io/milestone/)
- **What it is**: Spring's own repo (`repo.spring.io/milestone`) for pre-release artifacts not yet on Maven Central.
- **Usage here**: Declared in `pom.xml` `<repositories>` purely to pull `spring-ai 1.0.0-M6`.
- **Why it's pertinent**: Removed from the build as soon as Spring AI migrates to a GA BOM. Marked to delete in a TODO comment next to its declaration.

---

## Core frameworks

### 🌱 [Spring Boot 4](https://spring.io/projects/spring-boot)
- **What it is**: Opinionated Spring-based application framework (GA late 2025, current major).
- **Usage here**: Parent POM `spring-boot-starter-parent:4.0.5`. Default profile; everything under `com.mirador.*` assumes SB4 APIs.
- **Why it's pertinent**: Brings first-class Java 25 support, unified `spring-boot-starter-opentelemetry`, structured logging by default, and the new `RestTestClient`. Foundation of the whole stack.

### 🌱 [Spring Boot 3](https://docs.spring.io/spring-boot/docs/3.5.x/reference/html/) (compat)
- **What it is**: Previous Spring Boot major (Java 17+, still widely used).
- **Usage here**: `-Dsb3` profile swaps the BOM to `3.4.5`, applies the `java-overlays/sb3` overlay (replaces `@GetMapping(version = ...)`) and uses `micrometer-tracing-bridge-otel` instead of the SB4 OTEL starter.
- **Why it's pertinent**: Back-compat target for customers that haven't migrated. The overlay mechanism means we never fork the codebase.

### 🌱 [Spring Framework 7](https://spring.io/projects/spring-framework)
- **What it is**: Core Spring container (DI, AOP, MVC, transactions). Bundled by SB4.
- **Usage here**: Every `@Component`, `@Service`, `@Configuration`, `@Controller`, `@Transactional` in `com.mirador.*`.
- **Why it's pertinent**: It is Spring Boot. No alternative is realistic for a Java-centric team.

### 🌱 [Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)
- **What it is**: Servlet-based HTTP controller framework.
- **Usage here**: REST controllers under `com.mirador.customer`, `com.mirador.auth`, `com.mirador.api`. Pulled in via `spring-boot-starter-web`.
- **Why it's pertinent**: Battle-tested, virtual-thread friendly (SB4 wires `Executors.newVirtualThreadPerTaskExecutor()` by default), integrates natively with Spring Security and `springdoc`.

### 🐱 [Embedded Tomcat 11](https://tomcat.apache.org/tomcat-11.0-doc/index.html)
- **What it is**: Reference Java servlet container, embedded into the Spring Boot JAR.
- **Usage here**: Default transitive dependency of `spring-boot-starter-web`. Pinned to `11.0.21` via `<tomcat.version>` for CVE patches (CVE-2026-34483/486/487/500).
- **Why it's pertinent**: Stable, virtual-thread compatible, and the Spring Boot team tests against it. Pinning forward catches CVEs faster than waiting for the next SB point release.

### 🌱 [Spring Data JPA](https://spring.io/projects/spring-data-jpa)
- **What it is**: Repository abstraction over JPA with derived-query method names.
- **Usage here**: `CustomerRepository` and friends under `com.mirador.customer`. Pulled via `spring-boot-starter-data-jpa`.
- **Why it's pertinent**: Cuts repository boilerplate to one interface line per entity. Pairs naturally with Hibernate + HikariCP + Flyway.

### 🗄️ [Hibernate ORM](https://hibernate.org/orm/)
- **What it is**: Reference JPA provider — Java-object-to-SQL mapping.
- **Usage here**: Transitive default under `spring-boot-starter-data-jpa`. `spring.jpa.hibernate.ddl-auto=validate` enforces that entities match the Flyway-migrated schema at startup.
- **Why it's pertinent**: The only widely-adopted JPA implementation; Spring Data JPA is written against its specifics. `ddl-auto=validate` catches entity-vs-schema drift at boot.

### 🗄️ [HikariCP](https://github.com/brettwooldridge/HikariCP)
- **What it is**: High-performance JDBC connection pool, Spring Boot's default.
- **Usage here**: Implicit — `spring-boot-starter-jdbc`/`jpa` brings it in automatically.
- **Why it's pertinent**: Fastest pool in the JVM ecosystem; used unmodified because the defaults are already correct for this workload.

### 🔴 [Spring Data Redis (Lettuce)](https://spring.io/projects/spring-data-redis)
- **What it is**: Spring abstraction over a Redis client; Lettuce is the non-blocking default.
- **Usage here**: `spring-boot-starter-data-redis` + `StringRedisTemplate` in `com.mirador.customer.RecentCustomerBuffer` (LPUSH + LTRIM + LRANGE ring of last-10 customers).
- **Why it's pertinent**: Lettuce is netty-based, non-blocking, and safe to share across virtual threads. Moves the recent-customers buffer out of process memory so horizontal scaling works.

### 🌱 [Spring Cache Abstraction + Caffeine](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- **What it is**: `@Cacheable`-style method caching with a pluggable backend; Caffeine is the recommended in-process cache.
- **Usage here**: `spring-boot-starter-cache` + `com.github.ben-manes.caffeine:caffeine` for entity lookups (`findById`) that Redis does NOT handle.
- **Why it's pertinent**: Caffeine is the successor to Guava Cache — W-TinyLFU eviction, low GC pressure. Reserving Caffeine for hot entity reads and Redis for cross-pod state keeps responsibilities clean.

### 📨 [Spring Kafka](https://spring.io/projects/spring-kafka)
- **What it is**: Spring's Kafka integration: listener containers, `KafkaTemplate`, request-reply.
- **Usage here**: `spring-kafka` dependency; producers/consumers live in `com.mirador.messaging` (`KafkaConfig`, listener annotations).
- **Why it's pertinent**: Avoids hand-rolling `KafkaConsumer` loops. Integrates `@KafkaListener` with Micrometer observations out of the box.

### 🔔 [Spring WebSocket / STOMP](https://docs.spring.io/spring-framework/reference/web/websocket.html)
- **What it is**: Bidirectional messaging over WebSocket with the STOMP sub-protocol.
- **Usage here**: `spring-boot-starter-websocket`; real-time notification endpoint wired in `com.mirador.messaging.WebSocketConfig`. Frontend consumes it via SockJS.
- **Why it's pertinent**: STOMP gives publish/subscribe semantics over a single TCP connection; simpler than SSE for bi-directional use cases.

### 🔐 [Spring Security 7](https://spring.io/projects/spring-security)
- **What it is**: Authentication and authorization framework for Spring.
- **Usage here**: `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`; JWT + OIDC configured in `com.mirador.auth.SecurityConfig`.
- **Why it's pertinent**: Handles method-level authorization (`@PreAuthorize`), JWT resource-server validation, CORS, CSRF policy, and the filter chain. Rewriting this is how applications get CVEs.

### 🌱 [Spring HATEOAS + HAL Explorer](https://spring.io/projects/spring-hateoas)
- **What it is**: HATEOAS link generation plus a browseable HAL explorer UI.
- **Usage here**: `spring-boot-starter-hateoas` and `spring-data-rest-hal-explorer` — enables visual navigation of `/actuator/` and any HAL-capable endpoint.
- **Why it's pertinent**: Makes the actuator tree discoverable for operators without them memorising paths.

### 🌱 [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html)
- **What it is**: Production-ready operational endpoints (`/actuator/health`, `/actuator/prometheus`, `/actuator/info`, etc.).
- **Usage here**: `spring-boot-starter-actuator` + custom endpoint `com.mirador.observability.QualityReportEndpoint` exposed at `/actuator/quality`.
- **Why it's pertinent**: Kubernetes liveness/readiness probes, Prometheus scraping, info banners — all consume actuator endpoints. Custom endpoints let us expose build-time quality artefacts (SpotBugs XML, JaCoCo CSV) without a separate service.

### 🌱 [Spring Bean Validation (Jakarta Validation)](https://jakarta.ee/specifications/bean-validation/)
- **What it is**: Declarative validation via `@NotBlank`, `@Email`, `@Valid`, backed by Hibernate Validator.
- **Usage here**: `spring-boot-starter-validation`; DTOs in `com.mirador.customer` and request bodies in controllers.
- **Why it's pertinent**: Validation lives next to the field it protects (the DTO), not in the controller. Failures become 400s automatically via Spring MVC.

### 🌱 [Spring AOT / AutoConfiguration](https://docs.spring.io/spring-boot/reference/packaging/aot.html)
- **What it is**: Build-time ahead-of-time processing that bakes bean-definition metadata into the JAR, required for GraalVM native images.
- **Usage here**: `spring-boot-maven-plugin` `process-aot` goal in the `native` profile.
- **Why it's pertinent**: Without AOT, GraalVM can't see through Spring's reflection-based DI. AOT resolves all beans at build time so native-image has a closed world to compile.

---

## Persistence and data

### 🐘 [PostgreSQL 17](https://www.postgresql.org/)
- **What it is**: Open-source relational database.
- **Usage here**: `postgres:17` image in `docker-compose.yml`; managed Cloud SQL instance in `deploy/terraform/gcp/main.tf`. Drive via `org.postgresql:postgresql` JDBC driver (runtime scope).
- **Why it's pertinent**: Mature ACID DB with JSONB, partitioning, and logical replication. Spring Boot autoconfigures around it seamlessly. Version 17 is the current major.

### 🗄️ [Flyway](https://flywaydb.org/)
- **What it is**: Versioned SQL migration tool.
- **Usage here**: `spring-boot-starter-flyway` + `flyway-database-postgresql` (Flyway 10+ unbundled drivers). Migrations in `src/main/resources/db/migration/V*__*.sql`, including `V2__create_shedlock_table.sql`.
- **Why it's pertinent**: Schema evolution must be deterministic and peer-reviewable. Flyway file naming enforces uniqueness (never two `V1__`), and Spring runs it at startup before Hibernate's `validate`.

### 🗄️ [JDBC / JPA / Jakarta Persistence](https://jakarta.ee/specifications/persistence/)
- **What it is**: Standard Java DB APIs — raw JDBC and the higher-level JPA specification (Jakarta since Spring 6).
- **Usage here**: JPA via Spring Data, raw JDBC via `JdbcTemplate` in `com.mirador.resilience.ShedLockConfig`.
- **Why it's pertinent**: JPA for entity CRUD, JDBC for occasional surgical queries (ShedLock, health checks) — choosing the right tool per call.

### ☁️ [Cloud SQL (GCP)](https://cloud.google.com/sql)
- **What it is**: Managed PostgreSQL/MySQL service on Google Cloud.
- **Usage here**: Provisioned in `deploy/terraform/gcp/main.tf`; the GKE overlay injects a Cloud SQL Auth Proxy sidecar so the app connects via a local socket.
- **Why it's pertinent**: Point-in-time recovery, automated HA failover, and IAM-authenticated connections — none of which we would reliably rebuild on self-hosted Postgres.

### ☁️ [Cloud SQL Auth Proxy](https://cloud.google.com/sql/docs/postgres/sql-proxy)
- **What it is**: Google-provided sidecar that terminates a TLS tunnel to Cloud SQL using Workload Identity, exposing a local unix/TCP socket.
- **Usage here**: Injected as a container in the GKE overlay (`deploy/kubernetes/overlays/gke/`).
- **Why it's pertinent**: No DB password in transit, no VPC peering, no public IP — auth piggy-backs on K8s ServiceAccount → GCP IAM via Workload Identity. Best practice for Cloud SQL on GKE.

---

## Caching

### 🔴 [Redis 7](https://redis.io/)
- **What it is**: In-memory key-value store with rich data types (LIST, HASH, SET, SORTED SET, STREAMS).
- **Usage here**: `redis:7` in Compose; app connects via `SPRING_DATA_REDIS_HOST=redis`. Used by `RecentCustomerBuffer` (LPUSH + LTRIM + LRANGE), idempotency keys, and rate-limit buckets.
- **Why it's pertinent**: Sub-ms latency, cross-pod state, and the right data types for our use cases (ring buffer via LIST, distinct keys via SET).

### ☁️ [Memorystore for Redis](https://cloud.google.com/memorystore)
- **What it is**: Google Cloud's managed Redis service.
- **Usage here**: Provisioned by Terraform as `google_redis_instance.cache` in `deploy/terraform/gcp/main.tf` (size configurable via `redis_tier` / `redis_memory_size_gb`). The GKE overlay currently still inherits in-cluster Redis from the base `stateful/` overlay — the Memorystore host is plumbed through Terraform outputs but the Kustomize switchover is pending.
- **Why it's pertinent**: Managed failover and patching once we cut over from the in-cluster Redis deployment.

### 🔴 [RedisInsight](https://redis.io/insight/)
- **What it is**: Official Redis Labs web UI for browsing keys, TTLs, memory.
- **Usage here**: Container in `docker-compose.yml` (port 5540). Add connection host=redis port=6379.
- **Why it's pertinent**: Visibility into cache eviction and key TTL issues during development.

### 🔴 [Redis Commander](https://github.com/joeferner/redis-commander)
- **What it is**: Lightweight Node-based Redis UI with live command stream.
- **Usage here**: Compose service (port 8082), auto-connects to the `redis` service.
- **Why it's pertinent**: Lets us watch idempotency keys and rate-limit buckets mutate live. Smaller than RedisInsight, good complement for tail-the-log style debugging.

### 🗄️ [Caffeine](https://github.com/ben-manes/caffeine)
- **What it is**: High-performance in-process Java cache.
- **Usage here**: Backing cache for `@Cacheable` (see Spring Cache Abstraction entry).
- **Why it's pertinent**: Best eviction policy in the JVM space (W-TinyLFU) and effectively zero config.

---

## Messaging and real-time

### 📨 [Apache Kafka (KRaft)](https://kafka.apache.org/)
- **What it is**: Distributed log / event streaming platform.
- **Usage here**: `apache/kafka:4.0.0` in Compose, single-broker/controller KRaft mode (no ZooKeeper). App produces/consumes in `com.mirador.messaging`. In Kubernetes, a StatefulSet in `deploy/kubernetes/base/stateful/kafka.yaml`.
- **Why it's pertinent**: Async decoupling, replay, and consumer groups. KRaft eliminates the ZooKeeper operational tax — a real cost reduction for small clusters.

### 📨 [Kafka UI (Provectus)](https://github.com/provectus/kafka-ui)
- **What it is**: Web UI for topics, messages, consumer groups and ACLs.
- **Usage here**: `provectuslabs/kafka-ui:v0.7.2` in Compose (port 9080).
- **Why it's pertinent**: Much faster than `kafka-console-consumer.sh` scripts when you need to peek at topic content during development.

### 📨 [Google Cloud Managed Kafka](https://cloud.google.com/managed-service-for-apache-kafka) (rejected for now)
- **What it is**: GCP's managed Kafka offering.
- **Usage here**: Not used. `deploy/terraform/gcp/kafka.tf` has scaffolding but is commented out or disabled.
- **Why it's pertinent**: Tracked as a future migration once sustained throughput justifies the per-GB/s pricing. For now, in-cluster Kafka on GKE is adequate and cheap.

### 🔔 [Server-Sent Events (SSE)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
- **What it is**: One-way server-push over HTTP, native browser `EventSource`.
- **Usage here**: `CustomerController.stream()` exposes `GET /api/customers/stream` returning a `SseEmitter`; `SseEmitterRegistry` (thread-safe `CopyOnWriteArrayList`) fans out customer-created / enriched events with a 5-minute keep-alive timeout.
- **Why it's pertinent**: One-directional push fits our "customer feed" UI perfectly — simpler than WebSocket/STOMP, no library on the browser side (`new EventSource()`), works through corporate proxies that break WebSocket.

---

## Authentication and authorization

### 🔐 [JWT (JSON Web Token)](https://jwt.io/)
- **What it is**: Signed JSON claims, transmitted as `Authorization: Bearer <token>`.
- **Usage here**: Primary auth mechanism — validated by `JwtTokenProvider` (`com.mirador.auth`). Also accepted via Spring Security OAuth2 Resource Server for Keycloak/Auth0 tokens.
- **Why it's pertinent**: Stateless auth means any pod can validate any request without shared session state — essential for horizontal scaling.

### 🔐 [JJWT (io.jsonwebtoken)](https://github.com/jwtk/jjwt)
- **What it is**: Leading Java JWT library by Les Hazlewood.
- **Usage here**: Three-artifact split — `jjwt-api` (compile), `jjwt-impl` (runtime), `jjwt-jackson` (runtime) at version `0.12.6`.
- **Why it's pertinent**: The three-JAR split keeps the compile-time API small and prevents leaking `Impl` classes into consumer code. Used by `JwtTokenProvider`.

### 🔐 [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- **What it is**: Spring Security module that validates JWT bearer tokens against a JWKS endpoint.
- **Usage here**: `spring-boot-starter-oauth2-resource-server`. Activated when `KEYCLOAK_URL` (or `AUTH0_DOMAIN`) is set.
- **Why it's pertinent**: Lets the app accept Keycloak or Auth0 tokens without bespoke JWKS polling. Pulls in `nimbus-jose-jwt` transitively.

### 🔐 [Keycloak](https://www.keycloak.org/)
- **What it is**: Self-hosted open-source OIDC / SAML identity provider.
- **Usage here**: `quay.io/keycloak/keycloak:26.2.5` in Compose; realm `customer-service` imported from `infra/keycloak/realm-dev.json`. Two M2M clients — `api-gateway` and `monitoring-service`.
- **Why it's pertinent**: Demonstrates an OSS alternative to Auth0 for customers with on-prem requirements. Integration-tested via `testcontainers-keycloak`.

### 🔐 [Auth0](https://auth0.com/)
- **What it is**: Okta-owned SaaS identity provider.
- **Usage here**: Production path; `docs/auth0-action-roles.js` shows the post-login role-mapping action.
- **Why it's pertinent**: Zero-ops MFA, social login, and passwordless for the UI. Backend only needs to validate the issuer/JWKS.

### 🔐 [OIDC (OpenID Connect)](https://openid.net/developers/how-connect-works/)
- **What it is**: Identity layer on top of OAuth 2.0.
- **Usage here**: Both Keycloak and Auth0 speak OIDC — the same resource-server config works for both.
- **Why it's pertinent**: Standard means we can swap IDPs without code changes.

### 🔐 [Workload Identity Federation (WIF)](https://cloud.google.com/iam/docs/workload-identity-federation)
- **What it is**: Google Cloud mechanism that trades an external OIDC token (e.g. GitLab CI JWT) for a short-lived GCP access token.
- **Usage here**: Used by `terraform-*` and `deploy:gke` / `deploy:cloud-run` jobs — see the `external_account` credential block in `.gitlab-ci.yml`.
- **Why it's pertinent**: No long-lived service-account JSON keys in CI variables — the most common credential leak vector for GCP.

### ☸️ [`gke-gcloud-auth-plugin`](https://cloud.google.com/kubernetes-engine/docs/how-to/cluster-access-for-kubectl)
- **What it is**: Kubectl auth plugin that exchanges a gcloud access token for a K8s API server credential.
- **Usage here**: Installed by `deploy:gke` via `gcloud components install gke-gcloud-auth-plugin`.
- **Why it's pertinent**: Required since K8s 1.26 — the deprecated in-tree GCP auth was removed.

### 🔒 [Sigstore Fulcio](https://docs.sigstore.dev/certificate_authority/overview/)
- **What it is**: Sigstore's code-signing certificate authority that issues short-lived certs against OIDC identities.
- **Usage here**: `cosign:sign` CI job uses GitLab's `SIGSTORE_ID_TOKEN` to get a Fulcio-issued cert and sign image digests.
- **Why it's pertinent**: Keyless image signing — no long-lived signing keys to rotate or leak.

---

## Resilience and rate limiting

### 🛟 [Resilience4j](https://resilience4j.readme.io/)
- **What it is**: Fault-tolerance library providing `@CircuitBreaker`, `@Retry`, `@RateLimiter`, `@TimeLimiter`, `@Bulkhead`.
- **Usage here**: `io.github.resilience4j:resilience4j-spring-boot3:2.3.0`. Applied to outbound HTTP in `com.mirador.integration` and `com.mirador.resilience`.
- **Why it's pertinent**: Dependency-free (no Hystrix-style archaius), Spring-Boot-native, and composable via annotations. Hystrix is end-of-life.

### 🛟 [Bucket4j](https://bucket4j.com/)
- **What it is**: Token-bucket rate-limiting library.
- **Usage here**: `com.bucket4j:bucket4j-core:8.10.1`; per-IP 100 req/min filter — threshold chosen to match Cloudflare's default DDoS floor.
- **Why it's pertinent**: In-process (no extra infra for basic limits); backing store can be swapped to Redis later if we need cross-pod limits.

### ⏰ [ShedLock](https://github.com/lukas-krecan/ShedLock)
- **What it is**: Distributed lock over a shared data store, used to de-duplicate scheduled jobs in a cluster.
- **Usage here**: Two artefacts — `shedlock-spring` (annotation + AOP) and `shedlock-provider-jdbc-template` (JDBC backend). Backing table created by `V2__create_shedlock_table.sql`. Wired in `com.mirador.resilience.ShedLockConfig`.
- **Why it's pertinent**: Without this, a `@Scheduled` task running on 3 pods would fire 3× per tick.

### 🛟 [Retry and circuit-breaker patterns](https://martinfowler.com/bliki/CircuitBreaker.html)
- **What it is**: Standard availability patterns from the Release It! playbook.
- **Usage here**: Encoded via Resilience4j annotations on external calls (Auth0 JWKS refresh, etc.).
- **Why it's pertinent**: Prevents a flaky downstream from taking down the entire service thread-pool.

---

## Observability

### 📡 [Micrometer](https://micrometer.io/)
- **What it is**: Vendor-neutral metrics facade for the JVM (like SLF4J for metrics).
- **Usage here**: Bundled via `spring-boot-starter-actuator`; custom meters in `com.mirador.observability`.
- **Why it's pertinent**: Switching the metrics backend (Prometheus, Datadog, CloudWatch) is a config change, not a code rewrite.

### 📡 [Micrometer Prometheus registry](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)
- **What it is**: Micrometer bridge that exposes metrics in the Prometheus text format.
- **Usage here**: `io.micrometer:micrometer-registry-prometheus`; served at `/actuator/prometheus` and scraped by the OTel Collector in LGTM every 15 s.
- **Why it's pertinent**: Prometheus format is the de-facto lingua franca — every dashboard tool understands it.

### 📡 [`datasource-micrometer-spring-boot`](https://github.com/jdbc-observations/datasource-micrometer)
- **What it is**: Auto-configures Micrometer Observation around every JDBC `DataSource` call.
- **Usage here**: `net.ttddyy.observation:datasource-micrometer-spring-boot:2.2.1` — produces JDBC query-count and latency histograms + slow-query alerts.
- **Why it's pertinent**: Turns the DB into a first-class observable — queries that don't go through Spring Data still show up in Grafana.

### 📡 [OpenTelemetry (OTEL) SDK](https://opentelemetry.io/)
- **What it is**: CNCF-standardised telemetry API + SDK for traces, metrics and logs.
- **Usage here**: SB4 pulls the unified `spring-boot-starter-opentelemetry`. The SB3 profile falls back to `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`.
- **Why it's pertinent**: Vendor-agnostic wire format (OTLP) means we can redirect traces from LGTM locally to Grafana Cloud or Datadog in production via config only.

### 📡 [OTLP (OpenTelemetry Protocol)](https://opentelemetry.io/docs/specs/otlp/)
- **What it is**: Wire protocol for telemetry, over gRPC (4317) or HTTP (4318).
- **Usage here**: App pushes to LGTM port 4318; production pushes to Grafana Cloud direct OTLP endpoint with auth via `OTEL_EXPORTER_OTLP_AUTH`.
- **Why it's pertinent**: Single export pipeline for traces + logs + metrics — replaces bespoke Zipkin / Jaeger / Elastic APM agents.

### 📡 [OpenTelemetry Logback appender](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/logback)
- **What it is**: Logback appender that emits log events as OTLP records.
- **Usage here**: `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.16.0-alpha`; installed by `com.mirador.observability.OtelLogbackInstaller` at runtime.
- **Why it's pertinent**: Logs flow through the same OTLP pipeline as traces, so `trace_id` correlation works end-to-end in Grafana.

### 📡 [LGTM stack (Grafana Labs)](https://github.com/grafana/docker-otel-lgtm)
- **What it is**: Single container bundling Loki, Grafana, Tempo, Mimir and Pyroscope for development.
- **Usage here**: `grafana/otel-lgtm:0.22.1` in `docker-compose.observability.yml`; OTel Collector inside the container scrapes `/actuator/prometheus` every 15 s and ingests OTLP traces/logs.
- **Why it's pertinent**: One container gives you the full Grafana observability experience for local dev. In production we use Grafana Cloud (same stack, hosted).

### 📡 [Loki](https://grafana.com/oss/loki/)
- **What it is**: Grafana Labs log aggregation system, indexed by labels not content.
- **Usage here**: Logs ingested via OTLP (ENABLE_LOGS_ALL=true). Query via `{trace_id="..."}` to correlate with a trace.
- **Why it's pertinent**: Label index is cheap to operate vs Elasticsearch full-text — fine for structured logs.

### 📡 [Tempo](https://grafana.com/oss/tempo/)
- **What it is**: Grafana Labs distributed tracing backend.
- **Usage here**: Traces arrive via OTLP 4318; Grafana datasource provisioning tweaked to correlate `trace_id` with Loki labels.
- **Why it's pertinent**: Cost-effective trace store; links out to Loki for logs and Mimir for metrics from any trace view.

### 📡 [Mimir](https://grafana.com/oss/mimir/)
- **What it is**: Grafana Labs horizontally-scalable Prometheus storage, Prometheus-compatible API.
- **Usage here**: Replaces standalone Prometheus in the LGTM container (port 9091→9090 remap).
- **Why it's pertinent**: Multi-tenant, easier to scale than self-hosted Prometheus + Thanos.

### 📡 [Grafana](https://grafana.com/oss/grafana/)
- **What it is**: Dashboard and visualisation front-end for metrics, logs, traces and profiles.
- **Usage here**: Part of LGTM; dashboards provisioned from `infra/observability/grafana/dashboards-lgtm/`. Plugins installed: Infinity (REST calls), Dynamic Text (HTML panels), Button (trigger actions).
- **Why it's pertinent**: Single pane of glass across metrics + logs + traces + profiles.

### 📡 [Grafana Cloud](https://grafana.com/products/cloud/)
- **What it is**: Hosted LGTM with free tier generous enough for dev/demo.
- **Usage here**: Production OTLP target; creds injected via `GRAFANA_OTLP_AUTH` secret (base64 of `instanceId:apiToken`).
- **Why it's pertinent**: Zero-ops observability; free tier is enough for a service this size.

### 📡 [Pyroscope](https://grafana.com/oss/pyroscope/)
- **What it is**: Continuous CPU + memory profiler (part of Grafana stack).
- **Usage here**: `io.pyroscope:agent:2.1.2` embedded SDK; pushes JFR profiles every 10 s. Wired in `com.mirador.observability.PyroscopeConfig`.
- **Why it's pertinent**: Production profiling without a JVM `-javaagent` flag — good for finding hot methods and allocation sites we'd otherwise never spot.

### 📡 [Zipkin](https://zipkin.io/)
- **What it is**: Distributed tracing collector from Twitter.
- **Usage here**: Not used — LGTM/Tempo covers it.
- **Why it's pertinent**: Mentioned here because the observability doc historically referenced it; now redundant.

### ☕ [JFR (Java Flight Recorder)](https://docs.oracle.com/en/java/javase/21/jfapi/flight-recorder-api-programmers-guide.html)
- **What it is**: OpenJDK-native low-overhead profiling format.
- **Usage here**: Used by the Pyroscope agent to collect CPU/alloc/wall samples.
- **Why it's pertinent**: ~1 % overhead for always-on profiling — perfect for continuous profiling.

---

## AI and inference

### 🤖 [Spring AI](https://spring.io/projects/spring-ai)
- **What it is**: Spring's abstraction for LLM providers (`ChatClient`, `EmbeddingClient`).
- **Usage here**: `spring-ai-ollama-spring-boot-starter:1.0.0-M6`. Used to generate customer bios in `com.mirador.customer`.
- **Why it's pertinent**: Lets us swap Ollama for OpenAI/Anthropic/Bedrock by config. Pinned to milestone `1.0.0-M6` until we migrate to the renamed GA artifact (`spring-ai-starter-model-ollama`), which is tracked in `pom.xml` comments.

### 🤖 [Ollama](https://ollama.com/)
- **What it is**: Self-hosted LLM runtime (llama.cpp + model loader).
- **Usage here**: `ollama/ollama:0.9.0` in Compose, model `llama3.2` pulled on first start (~2 GB).
- **Why it's pertinent**: Free local inference means demos work without an OpenAI key. Model cache persisted via `ollama_data` volume.

### 🤖 [llama3.2](https://ollama.com/library/llama3.2)
- **What it is**: Meta's open-weights 3B-class chat model.
- **Usage here**: Default model the Compose `ollama` service pulls at startup.
- **Why it's pertinent**: Small enough to run on a laptop, good enough for bio-generation demos. Swap models by changing `ollama pull ...`.

---

## Testing

### 🧪 [JUnit 5 (Jupiter)](https://junit.org/junit5/)
- **What it is**: Current-generation Java test framework.
- **Usage here**: Pulled via `spring-boot-starter-test`; unit tests end in `*Test.java`, integration tests in `*ITest.java`.
- **Why it's pertinent**: Replaces JUnit 4 — cleaner lifecycle hooks, parameterised tests, extension model (used by ArchUnit, Testcontainers, Spring).

### 🧪 [AssertJ](https://assertj.github.io/doc/)
- **What it is**: Fluent assertion library (`assertThat(x).isEqualTo(y).isNotNull()`).
- **Usage here**: Default assertion library, bundled by `spring-boot-starter-test`.
- **Why it's pertinent**: Readable failure messages and rich collection/object matchers. Replaces Hamcrest's verbose style.

### 🧪 [Mockito](https://site.mockito.org/)
- **What it is**: Mock/spy/verify library for JVM objects.
- **Usage here**: `spring-boot-starter-test` bundle. Sonar job passes `-XX:+EnableDynamicAgentLoading` so Mockito's Byte Buddy agent can self-attach on Java 25.
- **Why it's pertinent**: Ubiquitous — every Java dev has the API memorised. The dynamic-agent flag is a Java 25 gotcha documented in the Sonar job.

### 🧪 [Testcontainers](https://testcontainers.com/)
- **What it is**: Java library that spins up real Docker services (Postgres, Kafka, Redis) for integration tests.
- **Usage here**: `testcontainers:junit-jupiter`, `testcontainers:postgresql`, `testcontainers:kafka` (pinned to `1.21.4` so kafka + core stay aligned). Plus `spring-boot-testcontainers` with `@ServiceConnection`.
- **Why it's pertinent**: ITs run against the SAME Postgres 17 and Kafka versions as prod, not an in-memory impostor. Catches driver-level and schema-level bugs H2 never would.

### 🧪 [`testcontainers-keycloak`](https://github.com/dasniko/testcontainers-keycloak)
- **What it is**: Community Testcontainers module for Keycloak.
- **Usage here**: `com.github.dasniko:testcontainers-keycloak:3.5.0`; used by auth ITs that exercise the OIDC flow.
- **Why it's pertinent**: Full OIDC integration test without a mocked IDP.

### 🧪 [EmbeddedKafkaBroker](https://docs.spring.io/spring-kafka/reference/testing.html)
- **What it is**: Spring Kafka's in-JVM Kafka broker for unit tests.
- **Usage here**: `spring-kafka-test`.
- **Why it's pertinent**: Faster than a container for message-format unit tests; we still use the Kafka Testcontainer for full integration flows.

### 🧪 [ArchUnit](https://www.archunit.org/)
- **What it is**: Library that asserts architectural rules (layering, dependency direction) in unit tests.
- **Usage here**: `archunit-junit5:1.4.0`; `ArchitectureTest` in tests — currently `@DisabledIfSystemProperty` on Java 25 pending ASM-over-ArchUnit fix.
- **Why it's pertinent**: Catches "controller imports repository directly" style drift automatically. Disabled on Java 25 because its hard-coded bytecode check chokes on class-file version 69.

### 🧪 [Spring Security Test](https://docs.spring.io/spring-security/reference/servlet/test/index.html)
- **What it is**: Spring Security helpers for MVC tests (`with(user(...))`, `csrf()`).
- **Usage here**: `spring-security-test` dependency.
- **Why it's pertinent**: Avoids generating real JWTs in tests — you can impersonate a principal inline.

### 🧪 [`spring-boot-webmvc-test` + RestTestClient](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- **What it is**: SB4-only MockMvc DSL replacement.
- **Usage here**: Declared in the `sb4` profile; used in `CustomerRestClientITest` (excluded from the SB3 test overlay).
- **Why it's pertinent**: The RestTestClient API is fluent, reactive-style, and plays well with virtual threads.

### 🧪 [Pitest](https://pitest.org/)
- **What it is**: Mutation testing tool — re-runs tests against tiny code mutations to measure how many are killed.
- **Usage here**: `pitest-maven:1.23.0` with `pitest-junit5-plugin:1.2.3`. Skipped by default; runs on `-Preport`. Output in `target/pit-reports/` and packaged under `META-INF/build-reports/`.
- **Why it's pertinent**: Raw line coverage is a weak signal. Pitest tells us whether the tests actually assert anything meaningful. Kept off the default build because it takes minutes.

### 🧪 [JaCoCo](https://www.jacoco.org/jacoco/)
- **What it is**: Bytecode-instrumentation code-coverage tool.
- **Usage here**: `jacoco-maven-plugin:0.8.14` with six executions (`prepare-agent`, `prepare-agent-integration`, `report`, `report-integration`, `merge`, `check`). Gate at 70 % merged instruction coverage.
- **Why it's pertinent**: Merged unit + IT coverage is the only accurate figure; unit-only would under-report because most real behaviour lives in `@SpringBootTest` paths.

### 🧪 [Surefire + Failsafe](https://maven.apache.org/surefire/)
- **What it is**: Maven plugins for unit tests (surefire) and integration tests (failsafe).
- **Usage here**: Surefire runs `*Test` in `test` phase; failsafe runs `*ITest` in `integration-test` and keeps going on failure so reports are always produced.
- **Why it's pertinent**: Convention-based split lets Docker-needing tests run only where Docker is available. Failsafe's tolerant mode guarantees JaCoCo data gets written.

---

## Quality analysis

### 🧹 [Checkstyle](https://checkstyle.sourceforge.io/)
- **What it is**: Source-level style enforcer using configurable rule XML.
- **Usage here**: `maven-checkstyle-plugin:3.6.0` with Checkstyle `10.26.1` (needed for Java 21 `case X when ...`). Uses `google_checks.xml`. Skipped by default; enabled by `-Preport-static`.
- **Why it's pertinent**: Catches style drift (imports, naming) before it becomes an argument in code review. Pinned version solves `NoViableAltException` on pattern guards.

### 🧹 [PMD](https://pmd.github.io/)
- **What it is**: Source-level static analyser for code smells, dead code, complexity.
- **Usage here**: `maven-pmd-plugin:3.26.0` with PMD core `7.20.0` (force-override to avoid `StackOverflowError` in 7.17). `targetJdk=21`; runs on `-Preport-static -Dcompat`. Output in `target/pmd.xml`.
- **Why it's pertinent**: Good complement to SpotBugs — PMD operates on source AST, SpotBugs on bytecode. Different bugs surface in each. Version pin is critical; without it 7.17 blows the stack on Java 25 generics.

### 🧹 [SpotBugs](https://spotbugs.github.io/)
- **What it is**: Bytecode-level static analyser (successor of FindBugs).
- **Usage here**: `spotbugs-maven-plugin:4.8.6.4` with ASM `9.8` override for Java 25 bytecode. Threshold `Medium`, fails on any Medium+ finding. Exclusions in `config/spotbugs-exclude.xml`.
- **Why it's pertinent**: Finds null dereferences, resource leaks, broken `equals/hashCode`, multi-threading bugs. Threshold `Medium` picks up real bugs without drowning in stylistic noise.

### 🧹 [Find Security Bugs](https://find-sec-bugs.github.io/)
- **What it is**: SpotBugs plugin with 130+ security-specific detectors (OWASP Top 10).
- **Usage here**: `com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0` wired as a SpotBugs dependency.
- **Why it's pertinent**: SAST with zero extra infra — just one more JAR on the SpotBugs classpath.

### 🧹 [SonarCloud](https://sonarcloud.io/)
- **What it is**: SaaS Sonar — static analysis dashboard (free for public repos).
- **Usage here**: `sonar-maven-plugin:5.1.0.4751`. Org `mirador1`, project key `mirador1_mirador-service`. Reads merged JaCoCo XML from both unit + IT. Exclusions defined in `pom.xml` `<sonar.exclusions>`.
- **Why it's pertinent**: Free tier gives us Quality Gate, security hotspots, and tech-debt tracking without self-hosting SonarQube.

### 🧹 [SonarQube Community (self-hosted)](https://www.sonarsource.com/products/sonarqube/)
- **What it is**: On-prem Sonar. Single-branch analysis only on Community.
- **Usage here**: `sonarqube:community` in `docker-compose.yml`, data in `sonar` Postgres database created by `infra/postgres/init-sonar.sql`. Elasticsearch needs `nofile` ulimit raised.
- **Why it's pertinent**: Local mirror of the SonarCloud analysis before pushing. Essential when iterating on a new ruleset offline.

### 🧹 [Semgrep](https://semgrep.dev/)
- **What it is**: Pattern-based SAST — rules written in simple YAML.
- **Usage here**: `semgrep/semgrep:latest` image, rulesets `p/java`, `p/spring`, `p/owasp-top-ten`, `p/secrets`. Output in GitLab SAST format for the Security Dashboard.
- **Why it's pertinent**: Free public rulesets, no account/token, ~1-2 min analysis. Chosen over Qodana (requires JetBrains account even on free tier).

### 🔒 [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/)
- **What it is**: SCA tool — scans Maven deps against the NVD for known CVEs.
- **Usage here**: `dependency-check-maven:12.1.1`. Fails build on CVSS ≥ 9. NVD DB cached in `.owasp-data/` (shared CI cache across branches). Committed baseline JSON report under `src/main/resources/META-INF/build-reports/`.
- **Why it's pertinent**: First-party tool is in-band (same Maven run), offline, and produces HTML + JSON we can archive. CVSS-9 gate blocks only the most severe issues so we don't wake on every transitive notice.

### 🦊 [GitLab SAST template](https://docs.gitlab.com/ee/user/application_security/sast/) (rejected-ish)
- **What it is**: GitLab's built-in Semgrep wrapper (`Security/SAST.gitlab-ci.yml`).
- **Usage here**: Included but `when: never` because the free-tier stub intentionally exits 1. Kept included so GitLab's Security Dashboard renders when/if we upgrade.
- **Why it's pertinent**: Our own `semgrep:` job does the real work; this include is a placeholder for the day we get GitLab Ultimate.

### 🦊 [GitLab Dependency Scanning template](https://docs.gitlab.com/ee/user/application_security/dependency_scanning/) (rejected-ish)
- **What it is**: GitLab's Gemnasium-based Maven CVE scanner.
- **Usage here**: Included but `when: never` (same free-tier stub problem). `owasp-dependency-check` is our effective substitute.
- **Why it's pertinent**: Keeps the hook in place so we can flip to `when: on_success` once we upgrade.

### 🔒 [GitLab Secret Detection (gitleaks)](https://docs.gitlab.com/ee/user/application_security/secret_detection/)
- **What it is**: GitLab's wrapper around gitleaks.
- **Usage here**: `Security/Secret-Detection.gitlab-ci.yml` include + our own `secret-scan` job running `zricethezav/gitleaks:v8.21.4` with project-specific `.gitleaks.toml`.
- **Why it's pertinent**: Two passes — GitLab's wrapper for the Security Dashboard, ours for the project-local allowlist and plain output.

### 🧱 [`dependency:analyze`](https://maven.apache.org/plugins/maven-dependency-plugin/analyze-mojo.html)
- **What it is**: Maven goal reporting used/unused/undeclared dependencies.
- **Usage here**: Execution in `pom.xml` writes `target/dependency-analysis.txt` into the JAR classpath, consumed by `QualityReportEndpoint` at `/actuator/quality`.
- **Why it's pertinent**: Flags stale `<dependency>` declarations that nobody uses — dead-weight trimming.

### 🧱 [`dependency:tree`](https://maven.apache.org/plugins/maven-dependency-plugin/tree-mojo.html)
- **What it is**: Maven goal dumping the full resolved dependency graph.
- **Usage here**: Writes `target/dependency-tree.txt` for inclusion at `/actuator/quality`.
- **Why it's pertinent**: When CVE scanners flag a transitive JAR, `dependency-tree.txt` tells you exactly which of our direct deps pulled it in.

### 🧱 [license-maven-plugin (Codehaus)](https://www.mojohaus.org/license-maven-plugin/)
- **What it is**: Generates a `THIRD-PARTY.txt` listing every dependency with its SPDX license.
- **Usage here**: `org.codehaus.mojo:license-maven-plugin:2.5.0`, bound to `generate-resources`, writes `THIRD-PARTY.txt` into `META-INF/build-reports/`.
- **Why it's pertinent**: License compliance audits; flags GPL/AGPL incompatibilities immediately.

### 🧱 [Maven `analyze` / `tree` / `license` reporting bundle](https://maven.apache.org/plugins/maven-dependency-plugin/)
- **What it is**: The three plugins above, all packaged into the JAR under `META-INF/build-reports/`.
- **Usage here**: Served by `QualityReportEndpoint` at `/actuator/quality` in a single JSON.
- **Why it's pertinent**: Operators get build-time quality metadata (tests, coverage, dependencies, licenses, CVEs) from the running app without hitting CI.

### 🦊 [GitLab Code Quality artifact](https://docs.gitlab.com/ee/ci/testing/code_quality.html)
- **What it is**: GitLab-specific JSON format (`gl-code-quality-report.json`) consumed by the MR Code Quality widget.
- **Usage here**: `code-quality` CI job converts SpotBugs + PMD + Checkstyle XML into GitLab's schema via inline Python.
- **Why it's pertinent**: MR diffs get inline annotations on changed lines without extra plugins.

---

## Documentation

### 📚 [Maven Site](https://maven.apache.org/plugins/maven-site-plugin/)
- **What it is**: Maven's HTML site generator (`mvn site`) combining project info + plugin reports.
- **Usage here**: Runs in the daily `generate-reports` CI job and pushes the result to the `reports/` branch. Includes Surefire, Failsafe, JaCoCo, SpotBugs, Javadoc, JXR (the SB4-incompatible ones — PMD/Checkstyle/OWASP — are omitted or patched in with `maven-antrun-plugin`).
- **Why it's pertinent**: One command, full report HTML, served by the `maven-site` nginx container at `http://localhost:8084`.

### 📚 [Javadoc](https://docs.oracle.com/en/java/javase/21/javadoc/javadoc.html)
- **What it is**: Java's native API-reference generator.
- **Usage here**: `maven-javadoc-plugin:3.11.2` with `linksource=true` — each class page links to its hyperlinked source.
- **Why it's pertinent**: Compodoc equivalent for the backend; browsable offline.

### 📚 [JXR](https://maven.apache.org/jxr/maven-jxr-plugin/)
- **What it is**: Cross-reference HTML source browser.
- **Usage here**: `maven-jxr-plugin:3.6.0`. Provides clickable source used by PMD/Checkstyle error links.
- **Why it's pertinent**: Turns PMD violations into "click the rule → see the offending line" navigation.

### 📚 [springdoc OpenAPI](https://springdoc.org/)
- **What it is**: Library that auto-generates an OpenAPI 3 spec from Spring MVC annotations and serves a Swagger UI.
- **Usage here**: `springdoc-openapi-starter-webmvc-ui:3.0.3`. Spec at `/v3/api-docs`, UI at `/swagger-ui.html`. Version 3.0.3 is pinned to patch DOMPurify CVEs in bundled Swagger UI.
- **Why it's pertinent**: Single source of truth for API contract — regenerated from code, not written by hand.

### 📚 [Swagger UI](https://swagger.io/tools/swagger-ui/)
- **What it is**: The browser UI bundled by springdoc — lets you try API calls interactively.
- **Usage here**: Served by springdoc at `/swagger-ui.html`.
- **Why it's pertinent**: Onboarding tool for new API consumers; no Postman collection to keep in sync.

### 📚 [ADRs (Architecture Decision Records)](https://adr.github.io/)
- **What it is**: Short Markdown files recording a single architectural choice and its rationale.
- **Usage here**: `docs/adr/` — decisions like Kustomize-over-Helm, buildx-over-Kaniko, Semgrep-over-Qodana live here.
- **Why it's pertinent**: `CLAUDE.md` design pattern — anyone (human or LLM) reading the repo can reconstruct why we did what we did without crawling Git history.

---

## Build and dependencies

### 🧱 [Apache Maven](https://maven.apache.org/)
- **What it is**: Java build tool using XML `pom.xml` declarative configuration.
- **Usage here**: `maven-wrapper` pins Maven version in `.mvn/wrapper`. CI uses `maven:3.9.14`. All lifecycle phases defined in `pom.xml`.
- **Why it's pertinent**: Incumbent Java build tool; massive plugin ecosystem. Gradle was considered and rejected — Maven's declarative model is easier to reason about at team-scale.

### 🧱 [Maven Wrapper (`mvnw`)](https://maven.apache.org/wrapper/)
- **What it is**: Script + descriptor that downloads a pinned Maven version on first run.
- **Usage here**: `mvnw` / `mvnw.cmd` at repo root; Dockerfile builder stage uses `./mvnw dependency:go-offline` then `./mvnw package`.
- **Why it's pertinent**: Any machine with a JDK can build — no global Maven install required. Pinning means every developer and CI runner uses the same Maven version.

### 🧱 [Maven BOM / dependencyManagement](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Bill_of_Materials_.28BOM.29_POMs)
- **What it is**: "Bill of Materials" pattern — a parent POM or imported BOM pins transitive versions.
- **Usage here**: `spring-boot-starter-parent:4.0.5` provides the main BOM; `<dependencyManagement>` pins ASM + protobuf for security overrides.
- **Why it's pertinent**: One place to audit versions. Renovate/Dependabot bumps land in one line instead of scattered `<version>`s.

### 🤖 [Renovate](https://docs.renovatebot.com/)
- **What it is**: Automated dependency updater (GitLab-hosted instance).
- **Usage here**: Config in `renovate.json`; `renovate-lint` CI job validates our config with `renovate-config-validator --strict`.
- **Why it's pertinent**: Proactive dep bumps reduce CVE exposure window. Lint catches config errors before the scheduled bot run.

### 🤖 [Dependabot](https://docs.github.com/en/code-security/dependabot) (rejected on GitLab)
- **What it is**: GitHub's dependency updater.
- **Usage here**: Not used — we're on GitLab.
- **Why it's pertinent**: Explicit non-choice; Renovate is the GitLab-native equivalent.

### 🧱 [`maven-compiler-plugin`](https://maven.apache.org/plugins/maven-compiler-plugin/)
- **What it is**: Java compiler plugin for Maven.
- **Usage here**: Configured to `<release>25</release>` with explicit `annotationProcessorPaths` for Lombok.
- **Why it's pertinent**: Explicit processor paths are mandatory on Java 25 (implicit discovery removed).

### 🧱 [`maven-failsafe-plugin`](https://maven.apache.org/surefire/maven-failsafe-plugin/)
- **What it is**: Integration-test runner (tolerates test failures so reports are still written).
- **Usage here**: Runs `*ITest` classes in `integration-test` phase.
- **Why it's pertinent**: JaCoCo `report-integration` depends on artifact output even on test failure — failsafe guarantees it.

### 🧱 [`maven-surefire-plugin`](https://maven.apache.org/surefire/maven-surefire-plugin/)
- **What it is**: Unit-test runner.
- **Usage here**: Runs `*Test` excluding `*ITest`. We override `default-test` execution to replace deprecated `systemProperties` with `systemPropertyVariables` (SUREFIRE-2190 workaround).
- **Why it's pertinent**: Kept fast by excluding Docker-dependent tests; their runtime moves to `failsafe`.

### 🧱 [`maven-resources-plugin`](https://maven.apache.org/plugins/maven-resources-plugin/)
- **What it is**: Copies and filters resource files.
- **Usage here**: Eight executions in `pom.xml` copy build artefacts (JaCoCo CSV, SpotBugs XML, Surefire XML, PMD/Checkstyle XML, OWASP JSON, Pitest XML, dependency-tree.txt, THIRD-PARTY.txt) into `META-INF/build-reports/` for `/actuator/quality`.
- **Why it's pertinent**: The JAR is self-describing — build-time quality data ships with the binary.

### 🧱 [`maven-antrun-plugin`](https://maven.apache.org/plugins/maven-antrun-plugin/)
- **What it is**: Runs arbitrary Ant tasks inside Maven.
- **Usage here**: Three executions: (1) creates `.owasp-data/README.md` the first time the NVD scan populates the cache; (2) copies the OWASP HTML into `target/site/` post-site (`dependency-check-maven` 12.x can't generate via `<reporting>` directly); (3) merges source overlays (`pre-java25`, `java17`, `sb3`) into `target/merged-sources/` for compat builds.
- **Why it's pertinent**: Surgical file-ops no Maven plugin covers natively. Chosen over `exec:exec` because Ant's `<copy if:set=...>` is conditional without shell plumbing.

### 🌱 [`spring-boot-maven-plugin`](https://docs.spring.io/spring-boot/maven-plugin/index.html)
- **What it is**: Spring Boot's Maven packaging plugin — produces the layered fat JAR.
- **Usage here**: `build-info` goal (exposes Git SHA + build time at `/actuator/info`); `process-aot` in the `native` profile.
- **Why it's pertinent**: Layered JARs enable Docker layer cache reuse; AOT is mandatory for GraalVM native image.

### 🧱 [GraalVM `native-maven-plugin`](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html)
- **What it is**: GraalVM's plugin that runs `native-image` during the Maven build.
- **Usage here**: In the `native` profile with `add-reachability-metadata` goal.
- **Why it's pertinent**: Registers reflection/serialisation/proxy hints so `native-image` can build a closed-world binary.

### 🧱 [`maven-dependency-plugin`](https://maven.apache.org/plugins/maven-dependency-plugin/)
- **What it is**: Dependency inspection plugin.
- **Usage here**: `tree` and `analyze-only` executions feed `/actuator/quality`.
- **Why it's pertinent**: Free visibility into what's actually on the classpath at runtime.

---

## Version control and commits

### 🌿 [Git](https://git-scm.com/)
- **What it is**: Distributed version control.
- **Usage here**: Repo at `gitlab.com/mirador1/mirador-service`. Permanent `dev` branch, auto-merge to `main` on green pipeline.
- **Why it's pertinent**: Fundamental. The `dev` branch pattern is documented in `CLAUDE.md` to prevent accidental deletion.

### 🪝 [lefthook](https://lefthook.dev/)
- **What it is**: Fast Git hooks manager (Go binary, no Node required).
- **Usage here**: `lefthook.yml` at repo root. Pre-commit: glab CI lint, hadolint, kubectl dry-run, terraform fmt+validate, env key parity, pom hardcoded-version scan, xmllint, gitleaks. Commit-msg: Conventional Commits regex. Pre-push: unit tests.
- **Why it's pertinent**: Catches the errors that previously took 4+ CI iterations to find. Fast-fail locally saves pipeline quota.

### 📝 [Conventional Commits](https://www.conventionalcommits.org/)
- **What it is**: Commit-message spec: `type(scope)?: subject` (feat, fix, docs, etc.).
- **Usage here**: Enforced in the `commit-msg` hook; powers release-please.
- **Why it's pertinent**: Semantic versioning becomes automatic — `feat` bumps minor, `fix` bumps patch, `!` bumps major.

### 📝 [commitlint](https://commitlint.js.org/)
- **What it is**: Node-based Conventional Commits linter.
- **Usage here**: `commitlint.config.mjs` documents the rules; actual enforcement is the pure-bash regex in `lefthook.yml` (avoids a `node_modules/` dependency for a Java project).
- **Why it's pertinent**: Config file documents intent even without the runtime — tooling can be swapped to the Node impl at any time.

### 🚀 [release-please](https://github.com/googleapis/release-please)
- **What it is**: Google's release-automation tool — reads Conventional Commits, opens a release MR, tags on merge.
- **Usage here**: `release-please` CI job on `main`. Uses `release-please-config.json` + `.release-please-manifest.json`.
- **Why it's pertinent**: Eliminates manual CHANGELOG.md maintenance and manual tagging.

### 🦊 [`glab`](https://gitlab.com/gitlab-org/cli)
- **What it is**: Official GitLab CLI.
- **Usage here**: `lefthook.yml` pre-commit runs `glab ci lint` on `.gitlab-ci.yml` changes. Used interactively to create/merge MRs.
- **Why it's pertinent**: Locally validates CI YAML against GitLab's API — one round-trip vs "push and wait for the pipeline".

### 🔒 [gitleaks](https://github.com/gitleaks/gitleaks)
- **What it is**: Fast secrets scanner for Git history and staged changes.
- **Usage here**: `zricethezav/gitleaks:v8.21.4` in the `secret-scan` CI job; `gitleaks protect --staged` in lefthook pre-commit. Allowlist in `.gitleaks.toml`.
- **Why it's pertinent**: Double gate (pre-commit + CI) keeps AWS/GCP keys and JWTs out of the repo. Pinned version (never `:latest`) for reproducibility.

---

## CI/CD

### 🦊 [GitLab CI](https://docs.gitlab.com/ee/ci/)
- **What it is**: GitLab's pipeline engine — YAML config at `.gitlab-ci.yml`, runners execute jobs.
- **Usage here**: ~60 jobs across stages `lint`, `test`, `integration`, `package`, `compat`, `native`, `sonar`, `reports`, `infra`, `deploy`. Two scheduled pipelines (native build + report gen).
- **Why it's pertinent**: Branch-aware workflow rules (`changes:` allowlist) skip docs-only commits. Primary CI since all repos live on GitLab.

### 🦊 [GitLab Runner (macbook-local)](https://docs.gitlab.com/runner/)
- **What it is**: Self-hosted GitLab runner on an Apple Silicon mac.
- **Usage here**: Tagged `macbook-local`; primary runner for docker-build, trivy, sbom:syft, grype:scan, dockle, cosign, secret-scan, renovate-lint, release-please, deploy:gke.
- **Why it's pertinent**: Zero marginal cost (no GitLab SaaS minutes burned). arm64 host for native compiles; `buildx --platform linux/amd64` handles GKE's amd64 target.

### 🦊 [GitLab SaaS shared runners](https://docs.gitlab.com/ee/ci/runners/) (rejected)
- **What it is**: GitLab-hosted Linux runners (amd64 and arm64).
- **Usage here**: Not tagged — we rely on the local runner for most jobs. `saas-linux-medium-amd64` is explicitly avoided because of monthly minute quota.
- **Why it's pertinent**: The global `CLAUDE.md` rule "use local runners, never rely on SaaS quota" is encoded here.

### 🐳 [`docker buildx`](https://docs.docker.com/buildx/working-with-buildx/)
- **What it is**: Docker's multi-platform build frontend using BuildKit.
- **Usage here**: `docker-build` CI job — `buildx build --platform linux/amd64` cross-compiles on the arm64 macbook runner.
- **Why it's pertinent**: Kaniko cannot cross-compile; using buildx + QEMU on the local runner gives us amd64 images for GKE without burning GitLab SaaS quota.

### 🖥️ [QEMU / binfmt](https://www.qemu.org/)
- **What it is**: User-mode CPU emulator enabling cross-arch container builds.
- **Usage here**: `docker run --privileged --rm tonistiigi/binfmt --install amd64` before `buildx build`.
- **Why it's pertinent**: Required once per runner to register the amd64 interpreter under binfmt_misc. Idempotent, so the `|| true` is safe.

### 🧱 [Kaniko](https://github.com/GoogleContainerTools/kaniko) (rejected for main image)
- **What it is**: Google's containerised Docker image builder, no daemon or privileged mode required.
- **Usage here**: Only used for `build-native` (GraalVM native image) where cross-compilation isn't needed. Rejected for the primary docker-build because it can't cross-compile (arm64 host → amd64 image fails on GKE).
- **Why it's pertinent**: Attractive on paper, but "builds on whatever arch the host is" is a landmine when the target cluster is a different arch.

### 🐳 [Docker-in-Docker / `docker:dind`](https://hub.docker.com/_/docker) (rejected)
- **What it is**: A privileged Docker daemon running inside a CI service container.
- **Usage here**: Deliberately not used. Integration tests use the host Docker socket (`/var/run/docker.sock`) via `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`.
- **Why it's pertinent**: The `.gitlab-ci.yml` comment spells out the DinD failure mode — inner container ports bind on `172.17.0.1` inside the DinD container and are unreachable from the job container. Socket binding is the durable fix.

### 🧹 [hadolint](https://github.com/hadolint/hadolint)
- **What it is**: Dockerfile linter written in Haskell.
- **Usage here**: Standalone `hadolint/hadolint:latest-alpine` container in the `lint` stage. `--failure-threshold error` matches the pre-commit hook.
- **Why it's pertinent**: Catches DL3002 (non-root USER), DL3007 (avoid `:latest`), DL3008 (pin apt versions) — all real bugs that would otherwise ship to production.

### 🧹 [Semgrep (CI)](https://semgrep.dev/docs/)
- **What it is**: See Quality analysis section.
- **Usage here**: `semgrep` CI job on the daily report schedule; artefact `gl-sast-report.json` populates GitLab's SAST widget.
- **Why it's pertinent**: Deep rules + GitLab integration — covered once above; listed here for completeness.

### 🔒 [Trivy](https://trivy.dev/)
- **What it is**: Aqua Security's image + dependency vulnerability scanner.
- **Usage here**: `aquasec/trivy:0.69.3` in `trivy:scan`. Scans the pushed registry image directly (no DinD). HIGH + CRITICAL severity.
- **Why it's pertinent**: Image-level CVE scan covers the base OS and JRE (OWASP Dep-Check only sees Maven deps). Pinned — never `:latest`.

### 🔒 [Grype](https://github.com/anchore/grype)
- **What it is**: Anchore's CVE scanner, driven by a generated SBOM.
- **Usage here**: `anchore/grype:v0.87.0` in `grype:scan`; reads the CycloneDX SBOM from `sbom:syft`.
- **Why it's pertinent**: Second opinion vs Trivy — Grype uses GitHub Advisory DB + NVD, catches Java-specific CVEs Trivy might miss. Cross-referencing both reduces false negatives.

### 🔒 [syft](https://github.com/anchore/syft)
- **What it is**: Anchore's SBOM generator.
- **Usage here**: `anchore/syft:v1.18.1` in `sbom:syft`, emits both `bom.cdx.json` (CycloneDX) and `bom.spdx.json` (SPDX).
- **Why it's pertinent**: SBOMs are mandated by NTIA and the EU CRA. Producing both formats covers all compliance frameworks.

### 🔒 [dockle](https://github.com/goodwithtech/dockle)
- **What it is**: Image-hygiene scanner (CIS Docker Benchmark, Dockerfile best practices).
- **Usage here**: `goodwithtech/dockle:v0.4.15` in the `dockle` job. Checks USER non-root, HEALTHCHECK, suspicious env vars. `allow_failure: true` — hygiene warnings, not blockers.
- **Why it's pertinent**: Complements Trivy (CVEs) with hygiene (posture). Catches the "forgot `USER` directive" class of regression.

### 🔒 [cosign](https://github.com/sigstore/cosign)
- **What it is**: Sigstore's tool for signing OCI artefacts with Fulcio-issued short-lived certs.
- **Usage here**: `gcr.io/projectsigstore/cosign:v2.5.0` in `cosign:sign`. Keyless mode using GitLab's `SIGSTORE_ID_TOKEN`.
- **Why it's pertinent**: Signed images let downstream verify provenance. Keyless = no long-lived signing key to rotate.

### 🔒 [CycloneDX / SPDX](https://cyclonedx.org/)
- **What it is**: Competing SBOM formats — CycloneDX from OWASP, SPDX from Linux Foundation.
- **Usage here**: `sbom:syft` emits both; GitLab CycloneDX artifact report renders in the UI.
- **Why it's pertinent**: Auditors may demand either — emit both and be done.

---

## Local dev environment

### 🐳 [Docker Compose](https://docs.docker.com/compose/)
- **What it is**: Multi-container orchestration file format (v3).
- **Usage here**: `docker-compose.yml` (infra: db, kafka, redis, ollama, keycloak, app, admin UIs, SonarQube, nginx report servers) + `docker-compose.observability.yml` (LGTM stack) + `docker-compose.runner.yml` (optional GitLab runner container).
- **Why it's pertinent**: One command stands up the full dev stack. Keeps `docker compose up -d` as the entry point for every new developer.

### 🐘 [pgAdmin](https://www.pgadmin.org/)
- **What it is**: Postgres web admin UI.
- **Usage here**: `dpage/pgadmin4:9.4` in Compose, port 5050. Pre-configured via `infra/pgadmin/servers.json` + `pgpassfile`.
- **Why it's pertinent**: Graphical query and schema browsing for devs without `psql` muscle memory.

### 🐘 [pgweb](https://sosedoff.github.io/pgweb/)
- **What it is**: Lightweight Postgres client with JSON REST API.
- **Usage here**: `sosedoff/pgweb:0.16.2` in Compose, port 8081, read-only. CORS wired for `localhost:4200` so the Angular app can query directly.
- **Why it's pertinent**: Simple and fast; JSON endpoint is the easy path for ad-hoc frontend queries in dev.

### 🔧 **`.env` / `.env.example`**
- **What it is**: Convention for per-developer environment variables.
- **Usage here**: Compose reads `.env`; `lefthook.yml` enforces key parity between `.env` and `.env.example`.
- **Why it's pertinent**: Every developer gets documented keys; nobody commits secrets. Parity check prevents drift.

### 🔧 **`run.sh`**
- **What it is**: Project-local task runner (Bash script).
- **Usage here**: `./run.sh check`, `./run.sh sonar`, `./run.sh site`, etc. Referenced from `lefthook.yml` pre-push (`./run.sh check`).
- **Why it's pertinent**: Canonical entry points so humans and CI both invoke the same commands.

---

## Containers and images

### 🐳 [Docker (daemon / CLI)](https://www.docker.com/)
- **What it is**: OCI-compliant container runtime, the de-facto developer-facing tool.
- **Usage here**: Docker Desktop on the macbook runner. `docker:28` image in `docker-build` CI job.
- **Why it's pertinent**: Still the lowest-friction way to build + ship images. buildx plugin bundled since 19.03.

### 🐳 [OCI (Open Container Initiative)](https://opencontainers.org/)
- **What it is**: Industry spec for image format + distribution.
- **Usage here**: `org.opencontainers.image.*` labels set in `Dockerfile` + CI build args.
- **Why it's pertinent**: Standard labels are parsed by cosign, Trivy, GitLab registry UI without extra configuration.

### ☕ [Eclipse Temurin (image)](https://hub.docker.com/_/eclipse-temurin)
- **What it is**: Adoptium's OpenJDK container images.
- **Usage here**: `eclipse-temurin:25-jdk` (builder + layers stages), `eclipse-temurin:25-jre` (runtime stage).
- **Why it's pertinent**: JRE-only runtime is ~180 MB smaller than JDK — no compiler in prod.

### 🐳 [Google distroless](https://github.com/GoogleContainerTools/distroless) (rejected)
- **What it is**: Minimal images with no shell, package manager, or utilities — just the runtime.
- **Usage here**: Not used. `Dockerfile` comment calls out that `distroless-java` only ships up to Java 21, which would fail on Java 25 bytecode.
- **Why it's pertinent**: Great concept, blocked on Google publishing `distroless-java25`. Re-evaluate when that lands.

### 🌱 [Spring Boot layered JAR](https://docs.spring.io/spring-boot/reference/packaging/efficient.html)
- **What it is**: Spring Boot 3+ layered JAR layout: `dependencies/`, `spring-boot-loader/`, `snapshot-dependencies/`, `application/`.
- **Usage here**: `java -Djarmode=tools -jar app.jar extract --layers --launcher` in the Dockerfile layers stage. Each layer becomes a separate Docker layer.
- **Why it's pertinent**: Re-builds only the `application/` layer on code change — dep layer stays cached.

### 🌱 [JarLauncher](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/loader/launch/JarLauncher.html)
- **What it is**: Spring Boot's launcher class that loads the exploded layered layout.
- **Usage here**: `ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]`.
- **Why it's pertinent**: Regular `java -jar app.jar` doesn't work after layer extraction; JarLauncher is the counterpart.

### 🐳 [OCI image labels](https://github.com/opencontainers/image-spec/blob/main/annotations.md)
- **What it is**: Metadata on the image manifest (`revision`, `created`, `source`, etc.).
- **Usage here**: Static labels in `Dockerfile`; dynamic `revision` and `created` injected by `docker buildx --label`.
- **Why it's pertinent**: Traceability from registry UI back to the exact CI commit. cosign signatures bind to the digest, labels let you resolve the image to a repo commit.

### 🌐 [Nginx](https://nginx.org/)
- **What it is**: Widely used reverse proxy and static web server.
- **Usage here**: `nginx:1.27-alpine` for three Compose services — `maven-site` (8084), `compodoc` (8086), and the `cors-proxy` in the observability compose.
- **Why it's pertinent**: Smallest usable static-file server; trivial to drop a `*.conf` next to it.

---

## Kubernetes and orchestration

### ☸️ [Kubernetes](https://kubernetes.io/)
- **What it is**: Container orchestration system (Pods, Deployments, Services, StatefulSets).
- **Usage here**: Primary deployment target. Manifests in `deploy/kubernetes/base/` and overlays in `deploy/kubernetes/overlays/{gke,eks,aks,local}`.
- **Why it's pertinent**: Portable across GKE / EKS / AKS / k3s — the deploy jobs just swap the credential mechanism.

### ☸️ [Kustomize](https://kustomize.io/)
- **What it is**: K8s manifest overlay tool, built into kubectl (`kubectl kustomize`).
- **Usage here**: `deploy/kubernetes/overlays/<provider>/kustomization.yaml` composes the base with provider-specific patches (Cloud SQL sidecar + cert-manager ingress for GKE, in-cluster Postgres for local/eks/aks).
- **Why it's pertinent**: Pure-YAML, declarative, no templating DSL. Comments in `.kubectl-apply` explain why we pipe `kubectl kustomize` through `envsubst` for variable expansion.

### 🚢 [Helm](https://helm.sh/) (rejected)
- **What it is**: K8s templating + release manager.
- **Usage here**: Not used. Kustomize covers our customisation needs.
- **Why it's pertinent**: Helm's Go-template syntax is a poor fit for teams already comfortable with YAML; Kustomize "patches not templates" is simpler. Decision recorded in `docs/adr/`.

### 🔧 [envsubst](https://www.gnu.org/software/gettext/manual/html_node/envsubst-Invocation.html)
- **What it is**: GNU `gettext` tool that substitutes `$VAR` in stdin.
- **Usage here**: `kubectl kustomize ... | envsubst | kubectl apply -f -` in `.kubectl-apply`. Substitutes `${IMAGE_REGISTRY}`, `${IMAGE_TAG}`, `${K8S_HOST}`, `${CLOUD_SQL_INSTANCE}`.
- **Why it's pertinent**: Kustomize doesn't resolve env vars — envsubst fills the gap without a full templating engine.

### ☸️ [kubectl](https://kubernetes.io/docs/reference/kubectl/)
- **What it is**: Kubernetes CLI.
- **Usage here**: `bitnami/kubectl:latest` for k3s deploy; pinned versions for AKS/EKS; dynamic download for GKE (`dl.k8s.io/release/stable.txt`). Pre-commit hook runs `kubectl apply --dry-run=client` on changed manifests.
- **Why it's pertinent**: Canonical K8s client; `--dry-run=client` is a zero-cost validation step.

### ☁️ [GKE Autopilot](https://cloud.google.com/kubernetes-engine/docs/concepts/autopilot-overview)
- **What it is**: Google Cloud fully-managed Kubernetes — no node management.
- **Usage here**: Default production target (`deploy:gke` runs automatically on main). Uses Workload Identity + Cloud SQL Auth Proxy sidecar.
- **Why it's pertinent**: Minimal ops — Google manages nodes, patching, autoscaling. Pays per-Pod resources rather than per-node.

### ☁️ [EKS (AWS Elastic Kubernetes Service)](https://aws.amazon.com/eks/)
- **What it is**: Managed K8s on AWS.
- **Usage here**: Manual deploy job `deploy:eks`; image `alpine/k8s:1.30.2`; overlay `deploy/kubernetes/overlays/eks/`.
- **Why it's pertinent**: Portability target; demonstrates the base manifests are cloud-agnostic.

### ☁️ [AKS (Azure Kubernetes Service)](https://azure.microsoft.com/en-us/products/kubernetes-service)
- **What it is**: Managed K8s on Azure.
- **Usage here**: Manual deploy job `deploy:aks` using `mcr.microsoft.com/azure-cli`; overlay `deploy/kubernetes/overlays/aks/`.
- **Why it's pertinent**: Third portability target.

### ☁️ [Cloud Run (GCP)](https://cloud.google.com/run)
- **What it is**: Serverless managed-container platform — scales to zero.
- **Usage here**: Manual deploy job `deploy:cloud-run`. Uses WIF for auth, reads DB password from Secret Manager.
- **Why it's pertinent**: Best for demos and low-traffic environments; zero idle cost.

### ☁️ [Fly.io](https://fly.io/)
- **What it is**: Global PaaS with anycast routing.
- **Usage here**: `deploy:fly` manual job using `flyio/flyctl:latest`.
- **Why it's pertinent**: Simplest possible prod deploy — good for side-project staging.

### ☸️ [k3s](https://k3s.io/)
- **What it is**: Rancher's minimal K8s distribution.
- **Usage here**: Generic `deploy:k3s` job for any bare-metal/k3s/Hetzner/OVH cluster with kubeconfig built from CI vars.
- **Why it's pertinent**: Covers the "I just want to run this on a Raspberry Pi" case.

### 🌐 [Ingress-nginx](https://kubernetes.github.io/ingress-nginx/)
- **What it is**: K8s Ingress controller backed by nginx.
- **Usage here**: `deploy/kubernetes/base/ingress.yaml` routes external traffic to the service; GKE overlay patches it with cert-manager + TLS.
- **Why it's pertinent**: Most-deployed ingress controller; zero-surprise HTTP routing.

### 🔒 [cert-manager](https://cert-manager.io/)
- **What it is**: K8s TLS automation (Let's Encrypt, in-house CAs).
- **Usage here**: GKE overlay annotates the ingress with `cert-manager.io/cluster-issuer` for automatic TLS provisioning.
- **Why it's pertinent**: TLS rotation is automatic — no "cert expired on Friday night" pages.

### ☸️ [StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- **What it is**: K8s controller for stable-identity pods (Kafka brokers, Postgres primaries).
- **Usage here**: `deploy/kubernetes/base/stateful/kafka.yaml`, `redis.yaml`, `keycloak.yaml`.
- **Why it's pertinent**: Stable DNS names and persistent volume claims — prerequisite for stateful workloads.

### ☸️ [HorizontalPodAutoscaler (HPA)](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)
- **What it is**: K8s autoscaler based on CPU/memory/custom metrics.
- **Usage here**: `deploy/kubernetes/base/backend/hpa.yaml`.
- **Why it's pertinent**: Autoscale under load; we sized `minReplicas/maxReplicas` and thresholds with realistic production numbers (see `CLAUDE.md` "justify every timeout" rule).

### ☸️ [PodDisruptionBudget (PDB)](https://kubernetes.io/docs/tasks/run-application/configure-pdb/)
- **What it is**: K8s object bounding simultaneous voluntary pod evictions.
- **Usage here**: `deploy/kubernetes/base/backend/poddisruptionbudget.yaml`.
- **Why it's pertinent**: Prevents a node drain from taking down all replicas at once.

### 🌐 [NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- **What it is**: K8s-native firewall rules for pod-to-pod traffic.
- **Usage here**: `deploy/kubernetes/base/networkpolicies.yaml`.
- **Why it's pertinent**: Default-deny posture — app only talks to what it actually needs.

### 🌐 [Cilium / Dataplane V2](https://cilium.io/)
- **What it is**: eBPF-based CNI; GKE Autopilot uses its fork called Dataplane V2.
- **Usage here**: Implicit on GKE Autopilot; enforces our NetworkPolicies.
- **Why it's pertinent**: Faster and more observable than iptables-based kube-proxy; powers the NetworkPolicy enforcement.

### ☸️ [Startup / liveness / readiness probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- **What it is**: K8s-triggered health checks that hit actuator endpoints.
- **Usage here**: `deploy/kubernetes/base/backend/deployment.yaml`. StartupProbe `failureThreshold=60 × periodSeconds=5 = 5 min` budget sized for cold-JVM + Flyway + node cold-start on GKE Autopilot.
- **Why it's pertinent**: Default startup budgets (~30 s) kill our pods before Flyway finishes on a cold node; the explicit 5-minute budget is documented in `CLAUDE.md`.

### 🔒 [Pod Security admission](https://kubernetes.io/docs/concepts/security/pod-security-admission/)
- **What it is**: K8s built-in admission controller enforcing the PodSecurity standard (replaces PodSecurityPolicy).
- **Usage here**: Namespace labels enforce `restricted` profile in `deploy/kubernetes/base/namespace.yaml`.
- **Why it's pertinent**: Declarative, no webhook to operate — replaces OPA/Gatekeeper for basic hardening.

### ☸️ [Kubernetes rollout](https://kubernetes.io/docs/tutorials/kubernetes-basics/update/update-intro/)
- **What it is**: Deployment strategy (RollingUpdate by default).
- **Usage here**: `kubectl rollout status deployment/mirador -n app --timeout=360s` in `.kubectl-apply`. 6 min is deliberate — JVM warmup + Flyway + image pull on cold nodes exceed the default 180 s.
- **Why it's pertinent**: Wrong timeout causes CI-false-positive failures that leave a half-rolled deployment.

---

## Supply chain security

### 🔒 [NVD (National Vulnerability Database)](https://nvd.nist.gov/)
- **What it is**: NIST's catalogue of CVEs with CVSS scores.
- **Usage here**: Primary feed for OWASP Dependency-Check (cached in `.owasp-data/`).
- **Why it's pertinent**: Canonical CVE source; `NVD_API_KEY` speeds up the first-run download.

### 🔒 [GitHub Advisory Database](https://github.com/advisories)
- **What it is**: GitHub's CVE + language-specific advisory feed.
- **Usage here**: Consumed by Grype when scanning the SBOM.
- **Why it's pertinent**: Often lists Maven-coord CVEs before they hit NVD.

### 🔒 [CVSS (Common Vulnerability Scoring System)](https://www.first.org/cvss/)
- **What it is**: 0-10 severity score assigned to each CVE.
- **Usage here**: OWASP Dep-Check `failBuildOnCVSS=9` — only block on Critical.
- **Why it's pertinent**: Tunable policy lets us track lower-severity issues without blocking deployment.

### 🔒 [SLSA / provenance](https://slsa.dev/)
- **What it is**: Supply-chain Levels for Software Artifacts — graded provenance attestations.
- **Usage here**: Not formally attested yet; cosign signing + SBOM publication is the groundwork toward SLSA L3.
- **Why it's pertinent**: Progressive journey — signed images are the first step, provenance attestations are next.

---

## Cloud providers and platforms

### ☁️ [Google Cloud Platform (GCP)](https://cloud.google.com/)
- **What it is**: Google's public cloud.
- **Usage here**: Primary production target — GKE Autopilot, Cloud SQL, Artifact Registry, Cloud Run. Provisioned by `deploy/terraform/gcp/`.
- **Why it's pertinent**: Autopilot + Cloud SQL + Workload Identity is the lowest-ops combination we've found for a small team.

### 🏗️ [Terraform](https://www.terraform.io/)
- **What it is**: HashiCorp's infrastructure-as-code tool.
- **Usage here**: `hashicorp/terraform:1.9` image. Configs in `deploy/terraform/gcp/` with state in GCS. CI jobs `terraform-plan` (auto) and `terraform-apply` (manual, `interruptible: false`).
- **Why it's pertinent**: Multi-cloud DSL with a massive provider ecosystem. Pinned to 1.9 because minor Terraform bumps have broken backward compatibility.

### ☁️ [GCS (Google Cloud Storage)](https://cloud.google.com/storage)
- **What it is**: GCP's object store.
- **Usage here**: Terraform remote state bucket (`TF_STATE_BUCKET`). Requires `quota_project_id` in the external_account creds (documented in CI workaround comment).
- **Why it's pertinent**: Standard Terraform backend; cheap and highly durable.

### ☁️ [GCP Artifact Registry](https://cloud.google.com/artifact-registry)
- **What it is**: Google's container + language-package registry.
- **Usage here**: Not currently wired in CI — `docker-build` pushes to GitLab Container Registry (see entry below), and the GKE deploy pulls from there via the `gitlab-registry` pull secret. Artifact Registry is the intended target when we move the Docker image push to GCP-native infra (e.g. Cloud Build triggered on tag). Terraform does not provision it yet.
- **Why it's pertinent**: Tight IAM integration with GKE; replaces the deprecated gcr.io. Kept in the glossary because it is where we plan to end up, and because some cosign/SBOM references assume a GCP-native registry path.

### 🦊 [GitLab Container Registry](https://docs.gitlab.com/ee/user/packages/container_registry/)
- **What it is**: Built-in container registry per GitLab project.
- **Usage here**: Primary push target in `docker-build` (`$CI_REGISTRY_IMAGE/backend`). Pulled from K8s via the `gitlab-registry` pull secret.
- **Why it's pertinent**: Zero config — credentials are injected by GitLab into every job.

### 🔐 [GCP Secret Manager](https://cloud.google.com/secret-manager)
- **What it is**: GCP's managed secret storage.
- **Usage here**: Cloud Run deploy reads `DB_PASSWORD` via `--set-secrets "DB_PASSWORD=mirador-db-password:latest"`.
- **Why it's pertinent**: Avoids K8s-secret-style base64-then-copy patterns.

### ☁️ [AWS](https://aws.amazon.com/)
- **What it is**: Amazon's public cloud.
- **Usage here**: EKS deploy target only — we don't provision any other AWS services. `aws-cli` in `alpine/k8s:1.30.2`.
- **Why it's pertinent**: Portability demonstration; no lock-in to GCP.

### ☁️ [Azure](https://azure.microsoft.com/)
- **What it is**: Microsoft's public cloud.
- **Usage here**: AKS deploy target; `mcr.microsoft.com/azure-cli` with service-principal auth.
- **Why it's pertinent**: Same as AWS — portability demonstration.

### ☁️ [CNCF (Cloud Native Computing Foundation)](https://www.cncf.io/)
- **What it is**: Foundation hosting K8s, OpenTelemetry, Helm, Prometheus, etc.
- **Usage here**: Most of our stack lives under CNCF umbrella projects.
- **Why it's pertinent**: Vendor-neutral tech survives vendor pivots.

---

## Networking

### 🌐 [RFC 1918 private address space](https://datatracker.ietf.org/doc/html/rfc1918)
- **What it is**: IANA-reserved IPv4 ranges (10.0/8, 172.16/12, 192.168/16).
- **Usage here**: GKE uses 10.x pod/service CIDRs provisioned in `deploy/terraform/gcp/main.tf`.
- **Why it's pertinent**: Standard private addressing — avoids collisions with customer VPCs.

### 🔐 [JWKS (JSON Web Key Set)](https://datatracker.ietf.org/doc/html/rfc7517)
- **What it is**: JSON document listing public keys a JWT issuer uses for signing.
- **Usage here**: Fetched from Keycloak or Auth0 by `spring-boot-starter-oauth2-resource-server`.
- **Why it's pertinent**: Key rotation is transparent — the server publishes the new key, clients refetch.

### 🌐 [CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)
- **What it is**: Browser-enforced cross-origin request policy.
- **Usage here**: `CORS_ALLOWED_ORIGINS` env var; `cors-proxy` sidecar in observability compose adds CORS headers to Loki/Docker API.
- **Why it's pertinent**: `CLAUDE.md` flags `"*"` as an antipattern — we always specify an explicit allowlist.

### 🔔 [STOMP](https://stomp.github.io/)
- **What it is**: Text-based messaging sub-protocol for WebSocket.
- **Usage here**: `spring-boot-starter-websocket` + `WebSocketConfig`; frontend SockJS client.
- **Why it's pertinent**: Pub/sub over a single socket with broker-style routing semantics.

### 🔔 [SockJS](https://github.com/sockjs/sockjs-client)
- **What it is**: WebSocket fallback library (long-polling, streaming).
- **Usage here**: Wrapped by Spring WebSocket when the client requests it.
- **Why it's pertinent**: Older proxies and corporate networks sometimes break raw WebSocket — SockJS is the resilience net.

### 🐳 [Docker bridge network (172.17.0.1)](https://docs.docker.com/network/network-tutorial-standalone/)
- **What it is**: Default Docker bridge gateway.
- **Usage here**: Testcontainers initial target; the `.gitlab-ci.yml` override `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` explains why Mac publishes to `127.0.0.1` instead and how to work around it.
- **Why it's pertinent**: Subtle cross-platform gotcha documented inline so future debugging is fast.

### 🐳 [`host.docker.internal`](https://docs.docker.com/desktop/networking/)
- **What it is**: Docker-injected hostname resolving to the host machine from inside a container.
- **Usage here**: Set as the Testcontainers host override in CI (`TESTCONTAINERS_HOST_OVERRIDE`) and on the LGTM compose (`extra_hosts: host.docker.internal:host-gateway`).
- **Why it's pertinent**: Works identically on Docker Desktop (Mac) and Docker 20.10+ on Linux — no per-platform code paths needed.

### 🔐 [IAM (Identity and Access Management)](https://cloud.google.com/iam)
- **What it is**: Per-cloud permissions model — who can do what to which resource.
- **Usage here**: GCP IAM for GKE / Cloud SQL / Artifact Registry. `gitlab-ci-deployer` service account permissions are listed in the `.terraform-base` comment.
- **Why it's pertinent**: Principle of least privilege is only real if the IAM grants are minimal.

---

## Cross-reference

The Angular frontend has its own glossary covering TypeScript/Angular-specific tech: <https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/technologies.md>.

Architecture-level decisions (Kustomize over Helm, buildx over Kaniko, Semgrep over Qodana, etc.) are recorded in `docs/adr/`. Those ADRs capture the one-off discussion; this glossary captures the steady-state picture.

For the running-app perspective — how these technologies surface at runtime — see `docs/architecture.md`, `docs/observability.md`, and `docs/security.md`.
