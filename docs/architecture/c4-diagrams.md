# C4 architecture diagrams

A four-level [C4](https://c4model.com/) view of Mirador, rendered as
Mermaid so the diagrams live next to the code and version with it.

GitHub + GitLab both render Mermaid blocks inline — open this file on
either platform and the diagrams paint themselves.

> **Reading order**: System Context → Container → Component → Code.
> Each level zooms in by ~10×.

---

## Level 1 — System Context

Where Mirador sits in the world. Who calls it, what it depends on.

```mermaid
C4Context
  title System Context — Mirador

  Person(operator, "Operator", "Demos the system, debugs incidents, runs chaos scenarios.")
  Person(developer, "Developer", "Reads ADRs, ships features via bin/ship.sh.")
  Person(visitor, "Read-only visitor", "Curious dev / recruiter; sees the GIF demo + READMEs.")

  System_Boundary(b1, "Mirador") {
    System(mirador, "Mirador platform", "Customer-management API + observability UI + LGTM stack.")
  }

  System_Ext(gitlab, "GitLab.com", "Canonical source of truth.<br/>CI runs on the local macbook-runner.")
  System_Ext(github, "GitHub mirror (mirador1)", "Read-only mirror.<br/>CodeQL + OSSF Scorecard run there.")
  System_Ext(gke, "GKE Autopilot (ephemeral)", "Brought up on demand for the live demo,<br/>destroyed nightly (ADR-0022).")
  System_Ext(grafana_cloud, "Grafana Cloud LGTM", "Public Tempo / Loki / Mimir tenant for prod traces.")
  System_Ext(ollama, "Ollama (llama3.2)", "Local LLM for the bio enrichment demo.")

  Rel(operator, mirador, "Uses")
  Rel(developer, gitlab, "git push, MR review")
  Rel(visitor, github, "Browses README + GIF")
  Rel(gitlab, github, "bin/ship.sh mirror push")
  Rel(developer, gke, "demo-up.sh on demand")
  Rel(mirador, ollama, "REST /api/generate", "for /customers/id/enrich")
  Rel(mirador, grafana_cloud, "OTLP push (prod)", "traces + logs + metrics")
```

---

## Level 2 — Container

Inside Mirador: which processes / services exist, how they talk.

```mermaid
C4Container
  title Container — Mirador (local stack)

  Person(user, "Operator")

  System_Boundary(mirador, "Mirador") {
    Container(ui, "Angular 21 UI", "TypeScript / zoneless", "Dashboard, customers, chaos, traces, logs, pipelines.")
    Container(backend, "Spring Boot 4 backend", "Java 25", "REST /customers, /auth, /actuator. JWT + OAuth2.")
    ContainerDb(db, "PostgreSQL 17", "JDBC", "Customers, audit events.<br/>Flyway migrations at startup.")
    ContainerDb(cache, "Redis 7", "Lettuce", "Cache, idempotency keys, JWT blacklist, rate-limit buckets.")
    Container(broker, "Apache Kafka (KRaft)", "spring-kafka", "customer.created event bus + request-reply for enrichment.")
    Container(lgtm, "LGTM all-in-one", "Grafana 12.4", "Tempo (traces) + Loki (logs) + Mimir (metrics) + Pyroscope (profiles).")
  }

  System_Ext(ollama, "Ollama (llama3.2)", "REST /api/generate")

  Rel(user, ui, "HTTPS :4200")
  Rel(ui, backend, "REST /api/* + JWT", "with X-API-Version header (ADR-0020)")
  Rel(backend, db, "JPA + Flyway", "JDBC :5432")
  Rel(backend, cache, "Spring Data Redis", ":6379")
  Rel(backend, broker, "KafkaListener and ReplyingKafkaTemplate", ":9092")
  Rel(backend, ollama, "WebClient", "for BioService")
  Rel(backend, lgtm, "OTLP HTTP push", ":4318")
  Rel(ui, lgtm, "Grafana Drilldown", "embedded panels")
```

---

## Level 3 — Component (backend slice)

One feature slice — `customer.create` — from controller to event bus.

```mermaid
C4Component
  title Component view - POST /customers (backend feature slice)

  Container(ui, "Angular UI", "Angular 21")
  ContainerDb(db, "PostgreSQL", "customer table")
  ContainerDb(cache, "Redis", "idempotency-key store")
  Container(broker, "Kafka", "customer.created topic")

  Container_Boundary(api, "Mirador backend") {
    Component(security, "SecurityFilterChain", "Spring Security", "JWT validation plus CORS allowlist incl. X-API-Version.")
    Component(rate, "RateLimitingFilter", "Bucket4j", "100 req per min per IP, see ADR-0019.")
    Component(idemp, "IdempotencyFilter", "Servlet filter", "POST and PUT only, replay-safe via Idempotency-Key header.")
    Component(controller, "CustomerController", "Spring MVC", "PostMapping on /customers route.")
    Component(service, "CustomerService", "Transactional", "Validates, persists, publishes event.")
    Component(repository, "CustomerRepository", "Spring Data JPA", "save Customer entity, INSERT row.")
    Component(producer, "CustomerEventProducer", "spring-kafka", "Sends ProducerRecord on customer.created topic.")
    Component(audit, "AuditService", "Async", "Writes to audit_event table.")
  }

  Rel(ui, security, "POST /customers plus Bearer JWT")
  Rel(security, rate, "valid token")
  Rel(rate, idemp, "under quota")
  Rel(idemp, controller, "key not seen, or replay returns cached")
  Rel(controller, service, "createCustomer dto")
  Rel(service, repository, "save entity")
  Rel(repository, db, "INSERT customer row")
  Rel(service, producer, "publishCreated id and payload")
  Rel(producer, broker, "send to customer.created")
  Rel(service, audit, "audit CUSTOMER_CREATED id")
  Rel(idemp, cache, "SETNX idem KEY with 24h TTL")
```

---

## Level 4 — Code (zoom)

For the deepest level we don't draw a Mermaid diagram — Compodoc
(UI side) and Javadoc (backend side) generate the per-class
reference automatically:

- Backend Javadoc: `mvn javadoc:javadoc` → `target/site/apidocs/`
- UI Compodoc: `npm run compodoc` → `docs/compodoc/`
- Compose: `compodoc` container at <http://localhost:9995> serves
  the UI's documentation when `--profile full` is used.

---

## Maintenance rule

These diagrams stay accurate by being **one-step-removed from the
code** — they describe SHAPES, not file paths. When a new container
appears (e.g. another LLM provider next to Ollama), update Level 2.
When a new component runs in a feature slice (e.g. SagaOrchestrator
on POST), update Level 3. **Do not add diagrams below Level 3** —
the code itself is Level 4.

If you add a new microservice or split Mirador, add Level 1 and 2
boxes for it; preserve the existing slice in Level 3 by renaming the
container boundary to the new service name.
