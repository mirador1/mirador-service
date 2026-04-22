# ADR-0044 — Hexagonal considered, feature-slicing retained (with ports-and-adapters lite on outbound events)

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Supersedes: [ADR-0008](0008-feature-sliced-packages.md) (feature-sliced
  package layout — 2026-04-16)
- Related: ADR-0011 (minimal `@Transactional` surface — analogous
  "keep framework coupling shallow" heuristic);
  [ADR-0051](0051-jpa-entity-as-domain-model.md) (intra-feature
  corollary: JPA entity = domain model inside one feature slice;
  cross-feature still uses DTOs via `port/`)

## Context

[ADR-0008](0008-feature-sliced-packages.md) chose **feature-sliced
packages** in April 2026 as the top-level organisation for
`com.mirador.*`. At 141 Java files with a growing surface (customer
CRUD + cache, Kafka events, WebSocket/SSE, HTTP integrations, JWT +
Auth0, observability, resilience), the recurring architectural
question is whether to migrate to full **Hexagonal / Clean
Architecture** (domain / application / adapters / infrastructure
layers with inverted dependencies).

The trigger for this ADR was a direct question during a refactor
session: "pourquoi pas Hexagonal ?". This ADR records the answer so
the same question doesn't get re-asked every quarter without context.

### What feature-slicing gives today

```
com.mirador/
├── auth/          SecurityConfig, JwtTokenProvider, AuthController
├── customer/      Entity + Repo + Service + Controller + DTOs
├── messaging/     KafkaConfig, listeners, publishers, events
├── integration/   HTTP clients (JsonPlaceholder, Bio)
├── observability/ Health indicators, RequestContext, AuditService
├── resilience/    RateLimiting, Idempotency, ScheduledJob
├── api/           ApiError, ApiExceptionHandler, OpenApiConfig
└── diag/          StartupTimings
```

Each feature is self-contained; Spring / JPA / Jackson annotations
mix freely with domain code. ArchUnit enforces cross-feature
boundaries (see `ArchitectureTest.java`).

### What full hexagonal would give

```
com.mirador/
├── domain/                   ← zero Spring/JPA/Jackson
│   ├── customer/   Customer (record), repository port, service
│   └── ...
├── application/              ← use cases (CreateCustomerUseCase, …)
├── adapters/
│   ├── in/         rest/, kafka/listener, websocket/
│   └── out/        persistence/, kafka/publisher, http/
└── infrastructure/           ← Spring @Configuration wiring
```

With ArchUnit rule: `adapters → application → domain`, never the
reverse.

## Decision

**Retain feature-slicing (ADR-0008) as the top-level organisation.
Apply ports-and-adapters _lite_ — extract domain ports only where
the swap is semantically meaningful — starting with outbound events
(`CustomerEventPort`). Do NOT migrate the whole codebase to
hexagonal.**

Formally, this ADR **supersedes ADR-0008**: the package layout stays
but the rule is tightened — feature packages MAY introduce a
`port/` sub-package for framework-free interfaces that decouple the
domain from its infrastructure impl.

### Sample applied in this ADR

```
com.mirador.customer.port.CustomerEventPort          ← interface, no Spring/Kafka
com.mirador.messaging.KafkaCustomerEventPublisher    ← implements port, @Retry, KafkaTemplate
com.mirador.customer.CustomerService                 ← depends on port, not impl
```

CustomerService's outbound-event dependency becomes an interface the
domain owns. Tests can inject an in-memory fake. The Kafka adapter
is a pure implementation detail.

### ArchUnit rule added

```java
@Test
void port_interfaces_must_not_depend_on_framework_packages() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("..port..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "com.fasterxml.jackson.."
        )
        .because("domain ports must be framework-free — see ADR-0044");
    rule.check(classes);
}
```

Compile-time guarantee that `port/` packages stay portable.

### What triggers a new port extraction

A `port/` interface is warranted when ONE of these is true:

1. The dependency has a plausible alternative implementation (Kafka
   → RabbitMQ, JPA → Mongo, REST → gRPC).
2. The domain would benefit from a pure unit test that doesn't load
   Spring context.
3. The infrastructure type leaks framework concerns (topic names,
   connection strings, retry annotations) into domain code.

If none apply, the feature-slicing pattern from ADR-0008 stays —
no ceremony, no extra interface. This is deliberate ADR-0026 spirit
("scope limit — keep framework coupling shallow").

## Alternatives considered

### A) Full hexagonal migration

**Rejected.** Cost estimate:

- 141 `.java` files → ~260-280 after decomposition (use cases +
  ports + adapters + mappers). Near 2× file count.
- DTO explosion: CreateCustomerRequest → CreateCustomerCommand →
  Customer → CustomerEntity → CustomerDto per round trip, with
  mappers between each layer.
- JPA friction: Spring Data JPA wants `@Entity` classes in the
  same package as `@Repository`. Keeping entities pure forces
  MapStruct mappers from `domain.Customer` → `adapters.out.
  persistence.CustomerEntity` at every read/write.
- 2-3 weeks of mechanical refactor for a CRUD backend whose
  business logic is thin.
- SonarCloud coverage trend splits if the project key changes (it
  doesn't here — `groupId:artifactId` is stable — but other
  hexagonal migrations commonly trip on this).
- No functional benefit: nothing breaks today, no feature gets
  unlocked, no compliance issue.

Hexagonal shines when domain logic is RICH (banking calculations,
pricing engines, compliance workflows, long-running sagas). CRUD
over Postgres + Kafka events doesn't meet that bar.

### B) Hexagonal lite on outbound events only (accepted)

Extract one port (`CustomerEventPort`) + one adapter
(`KafkaCustomerEventPublisher`). Demonstrates the pattern. Cost:
~half a day. Value: (a) reviewers see the pattern in situ, (b)
ArchUnit rule prevents port-layer framework creep, (c) future port
extractions follow the same shape.

### C) Rename packages to `org.mirador.*`

**Rejected separately** (not this ADR's scope, but evaluated in the
same session). 141 .java files + 319 text references across 157
files to rename for a cosmetic convention change. Java's `com.` vs
`org.` distinction is not strictly enforced in practice
(`com.intellij` is non-commercial, `org.springframework` is
commercial-backed). No driver.

### D) Adopt DDD tactical patterns (Value Objects, Aggregates, Domain
Events) without full hexagonal

**Partially adopted.** `Customer` is effectively an aggregate root
already. `CustomerCreatedEvent` is a domain event. Full DDD
tactical patterns (Specifications, Domain Services, Repositories as
collection interfaces) are overkill here — no rich domain logic to
encapsulate.

### E) Keep the exact ADR-0008 layout, no ports at all

**Rejected.** The `CustomerEventPort` extraction is cheap (1
interface, 1 rename, 1 ArchUnit rule) and provides a visible
anchor for "how we'd introduce a port if needed". Reviewers who
scan the codebase for architectural patterns see it once instead
of nowhere.

## Consequences

### Positive

- Architectural conversation is recorded; next time someone asks
  "why not hexagonal?" the answer is a link, not a rerun.
- `CustomerEventPort` is a working sample of the pattern —
  concrete, reviewable, not just "we could".
- ArchUnit rule on `port/` packages prevents drift: if anyone adds
  a Spring or Kafka import to a future port, the build fails.
- ADR-0008 superseded cleanly — feature-slicing stays, but with
  the tighter rule that ports are legal and belong under `port/`.

### Negative

- One more sub-package convention (`port/`) for new contributors to
  learn. Mitigated by the ArchUnit rule + this ADR link in
  `customer/port/package-info.java`.
- Risk of port inflation: junior reviewer sees the `port/` pattern
  and starts extracting ports for every Spring bean dependency.
  Mitigated by the three triggers in the Decision section ("new
  port only when…").
- Pattern asymmetry: outbound events go through a port, outbound
  HTTP calls still don't. If the `JsonPlaceholderClient` ever
  grows a second implementation (mock for local dev?) it would
  become the next port candidate.

### Neutral

- The `customer/port/` sub-package adds one directory. File count
  change: +1 interface, +1 package-info, -1 concrete class moved
  to `messaging/`. Net ≈ 0.

## Operational pattern — when to add a port

```
# Before adding a port, the answer to ALL THREE must be YES:
Q1: Does the dependency have a plausible second implementation? (Kafka
    → RabbitMQ, JPA → Mongo, REST → gRPC)
Q2: Would a framework-free unit test of this code genuinely add value?
Q3: Is the impl leaking framework concerns (topic names, connection
    strings, annotations) into domain code?
```

If all YES → extract a `port/` interface + dedicated adapter.
Otherwise → feature-slice it with direct framework usage (the
ADR-0008 default).

## Revisit criteria

- A feature accumulates enough business logic to benefit from use
  cases as a separate layer (e.g. multi-step workflows with
  compensation, complex validation rules, domain calculations) →
  apply hexagonal to that feature ONLY, keep the rest on
  feature-slicing.
- Infrastructure swap becomes concretely planned (e.g. move from
  in-cluster Kafka to Managed Kafka, Postgres → Spanner) → extract
  ports for the impacted adapters ahead of the migration.
- Team grows past single-dev contributors and the "implicit
  boundaries" of feature-slicing start to drift → re-evaluate full
  hexagonal.
- If 3+ ports exist but no ArchUnit rule prevents framework creep
  → tighten the rule or accept the inconsistency explicitly.

## References

- `ADR-0008` (superseded) — original feature-sliced decision.
- `ADR-0011` — analogous "keep framework coupling shallow"
  heuristic applied to `@Transactional`.
- `ADR-0026` — scope limit (app doesn't know about third-party
  tools) — same spirit of shallow coupling.
- `src/main/java/com/mirador/customer/port/CustomerEventPort.java`
  — concrete port sample.
- `src/main/java/com/mirador/messaging/KafkaCustomerEventPublisher.java`
  — concrete adapter sample.
- `src/test/java/com/mirador/ArchitectureTest.java` — ArchUnit
  rule enforcing port-layer purity.
- Hexagonal / Ports-and-Adapters, Alistair Cockburn (2005) —
  https://alistair.cockburn.us/hexagonal-architecture/
- Clean Architecture, Robert C. Martin (2017) — the concentric-
  layer variant; the dependency-inversion rule is the invariant
  we DO enforce (on ports only, not across the whole codebase).
