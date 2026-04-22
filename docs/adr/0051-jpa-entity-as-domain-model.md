# ADR-0051 — JPA entity = domain model (accept the coupling)

- Status: Accepted
- Date: 2026-04-22
- Deciders: @benoit.besson
- Related:
  [ADR-0008](0008-retire-observability-ui-in-favour-of-grafana.md)
  (feature-sliced packages — 2026-04-16),
  [ADR-0044](0044-hexagonal-considered-feature-slicing-retained.md)
  (feature-slicing + Hexagonal Lite — 2026-04-21),
  [Clean Code + Clean Architecture audit 2026-04-22](../audit/clean-code-architecture-2026-04-22.md)
  (the trigger — audit proposal #2)

## Context

The 2026-04-22 Clean Code + Clean Architecture audit flagged two
classes that are **simultaneously JPA entities AND domain models**:

- `com.mirador.auth.RefreshToken`
- `com.mirador.customer.Customer`

Both carry `@Entity`, `@Column`, `@GeneratedValue` annotations (JPA —
an infrastructure concern in Clean Architecture vocabulary — "how
the data persists to Postgres") and are used directly as the return
type of domain services (`CustomerService.findById(id) → Customer`).

Strict Clean Architecture (Uncle Bob's dependency rule — "inner
layers must know nothing about outer layers") says the **domain
model has zero framework dependencies**. Under that rule the
`@Entity` annotation is a leak from the outer persistence layer into
the domain core, and the "correct" layout would be:

```
CustomerEntity.java   (JPA entity, persistence layer, @Entity + @Column)
Customer.java         (pure domain record, no annotations)
CustomerMapper.java   (entity ↔ domain bidirectional mapper)
```

Mirador deliberately does **NOT** do this. This ADR records the
decision so future maintainers don't file the un-separated
`@Entity` on `Customer.java` as tech debt waiting for a split.

## Decision

**Accept JPA entity = domain model.** No separate
`CustomerEntity` / `Customer` split; no entity-to-domain mapper;
`@Entity`-annotated classes flow freely across service boundaries
within the same feature slice.

Cross-feature boundaries still get the Hexagonal Lite treatment
([ADR-0044](0044-hexagonal-considered-feature-slicing-retained.md)):
`port/` sub-packages expose DTOs or events, not entities. **This
ADR only covers intra-feature use.**

### What this decision actually looks like

```java
// com.mirador.customer.Customer                (JPA entity)
@Entity
public class Customer {
  @Id @GeneratedValue(strategy = IDENTITY) private Long id;
  private String name;
  private String email;
  @Column(insertable = false, updatable = false) private Instant createdAt;
  // getters/setters via Lombok
}

// com.mirador.customer.CustomerService         (domain service)
public CustomerDto findById(Long id) {
  return repository.findById(id)                // returns Customer, not CustomerEntity
                   .map(CustomerDto::from)       // DTO at the REST boundary
                   .orElseThrow(...);
}

// com.mirador.customer.port.CustomerEventPort  (outbound port, ADR-0044)
public interface CustomerEventPort {            // publishes DTO, NOT the entity
  void publishCreated(CustomerCreatedEvent event);
}
```

Inside the `customer/` package: `Customer` flows freely. Across
feature boundaries (Kafka publish, REST serialise,
ObservabilityService audit, …): always a DTO, never the entity.

## Alternatives considered

### Option A — Full Clean Architecture split (rejected)

Introduce `CustomerEntity` (JPA) vs `Customer` (domain) with a
bidirectional mapper.

Cost audited:

- **~120 LOC of mapper + test per feature** (Customer, RefreshToken,
  plus any future persisted aggregate). With ~6 feature slices
  eventually persisted, ≈ 700 LOC of pure translation code.
- **MapStruct or manual loops** — both add a category of bug
  (field drift when the schema changes but the mapper forgets a
  field) that the "same class" approach doesn't have.
- **Dependency rule gain** ≈ zero in practice: Mirador is a
  monolith with one persistence tech (Postgres via JPA). Swapping
  JPA for, say, MongoDB's `@Document` is a theoretical future that
  historically hasn't happened in this project, and when it does
  happen it usually requires a schema redesign that would dwarf the
  mapper layer anyway.
- **Lazy-loading quirks don't disappear** — they move from "leaks
  into `Customer` via `@OneToMany` lazy collection" to "leaks into
  `CustomerEntity.toDomain()` which must fully hydrate before
  mapping". Same problem, different symptom.

Verdict: **high cost, low present-value benefit**. Revisit only if
(a) a second persistence tech lands alongside JPA, or (b) a
non-trivial domain invariant needs to be expressed without the
framework in the way.

### Option B — Use Java records for entities (rejected, technically impossible)

JPA requires:

1. A **no-arg constructor** (reflective instantiation by the provider).
2. **Mutable fields** (JPA sets `@GeneratedValue` IDs after persist).

Java records are immutable and have no no-arg constructor. Hibernate
6 experimental support for records exists but is not production-ready
on Spring Boot 4 / Hibernate 6.x — and even if it were, the
"immutable" property is fundamentally at odds with JPA's
write-through model. Lombok's `@NoArgsConstructor` + `@Setter`
synthesises what records would eliminate, which is the current
choice.

### Option C — Keep entities, use DTO everywhere (status quo)

`CustomerService.findById` returns `CustomerDto`, NOT `Customer`.
The entity never crosses the service boundary — it stays as an
internal persistence detail.

This is actually **closer to what we do** — see the code snippet
above. The subtle difference is within the `customer/` package
itself: `Customer` flows between `CustomerService`, `CustomerRepository`,
and the `CustomerMapper` that builds `CustomerDto`. That's accepted
coupling within one feature slice, per ADR-0044.

## Consequences

### Positive

- **No mapper layer** — the `Customer` field set IS the schema.
  Adding a column is 2 edits (Flyway migration + entity field), not
  4 (+ entity + mapper + domain class + mapper test).
- **Lombok noise is concentrated** — only on classes that MUST be
  mutable for JPA. Domain-ish DTOs (`CustomerDto`, `CustomerSummary`,
  `BatchImportResult`, …) stay as Java records.
- **Spring Data JPA interface projections** (see
  `CustomerSummary.java`) work out of the box without a mapper step.

### Negative (accepted)

- **Schema change = domain change.** Renaming a column is a compile
  error across every place the entity is read — fine for a solo
  project, possibly painful for a team ≥ 3 where the entity is
  touched concurrently.
- **Lazy-loading + Jackson serialisation mix** — JPA's
  `@OneToMany(fetch = LAZY)` would serialise as a `HibernateLazyInitializer`
  proxy if the entity ever reached a REST controller directly. Current
  mitigation: **controllers always return DTOs**, never entities.
  See `CustomerController` — every `@GetMapping` returns a DTO or a
  `Page<CustomerDto>`. This is the contract that makes Option C work.
- **Clean Architecture purists will call it tech debt.** Cross-ref
  this ADR in code review when the question comes up.

### Revisit triggers

Re-open this decision when any of:

- A second persistence tech lands (MongoDB for events, DynamoDB
  for session state, …) and the domain needs to be agnostic.
- An `@Entity` accidentally leaks into a REST response (Jackson
  serialises a lazy proxy, `LazyInitializationException` at runtime)
  — that's a bug to fix, but repeated incidents mean the DTO
  boundary is wearing thin and a mapper layer would enforce what
  discipline is currently doing by hand.
- Team size grows past ~3 contributors and concurrent entity edits
  generate merge conflicts in mapper-less code.
- Domain invariants need expressing that don't fit in a JPA-shaped
  class (e.g. a `Customer` that's actually a sum type — individual
  vs corporate — would be a Kotlin sealed class in full Clean Arch,
  harder to model as a JPA entity).

## Invariants

To make this decision safe, these invariants MUST hold:

1. **Entities never cross feature boundaries.** `customer.port.*`
   publishes DTOs / events, not `Customer`. ArchUnit enforces this
   (see `ArchitectureTest.java`).
2. **Entities never reach REST controllers.** Every `@GetMapping` /
   `@PostMapping` returns a DTO or `Page<DTO>`. Enforced by reading
   controller signatures; Sonar flags `@Entity` as a return type.
3. **Entities stay simple.** No `@OneToMany` lazy collections
   unless also guarded by a DTO mapper — the REST serialisation
   risk isn't worth the ORM convenience. Prefer explicit repository
   methods over lazy navigation.

Violating any of these invariants re-introduces the coupling cost
we're saving; they're the reason this choice is sustainable.

## References

- [ADR-0008](0008-retire-observability-ui-in-favour-of-grafana.md)
  — feature-sliced packages (why we slice by feature, not by layer)
- [ADR-0044](0044-hexagonal-considered-feature-slicing-retained.md)
  — Hexagonal Lite (when `port/` emerges)
- [Clean Code + Clean Architecture audit 2026-04-22](../audit/clean-code-architecture-2026-04-22.md)
  — audit §"JPA entities directly used as domain models" (proposal #2)
- Robert C. Martin, *Clean Architecture* (2017) — dependency rule
- Vaughn Vernon, *Implementing Domain-Driven Design* (2013) — ch. 6
  on entities vs value objects (the academic DDD counter-argument
  to this ADR)
- Thorben Janssen, "JPA and records" (SipsFrom...) — why records
  still don't work as entities in Spring Boot 4 / Hibernate 6.x
