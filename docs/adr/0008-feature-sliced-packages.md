# ADR-0008: Feature-sliced package layout in `com.mirador.*`

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

The default Spring Boot archetype produces a layered package layout:

```
com.example.app.controller
com.example.app.service
com.example.app.repository
com.example.app.model
```

At small scale this is fine. Once the app grows past ~30 classes, the
"controller/" package fills with unrelated concerns (auth, customer
CRUD, Kafka enrichment, rate limit endpoints) and code navigation
degrades.

## Decision

Use **feature slicing**. One top-level package per business capability:

```
com.mirador.customer          ← CRUD + events + DTOs for the customer aggregate
com.mirador.auth              ← JWT, UserDetails, SecurityConfig
com.mirador.messaging         ← Kafka producer/consumer, topic config
com.mirador.ratelimit         ← Filter, config, endpoint
com.mirador.observability     ← Actuator customisations, metrics wiring
com.mirador.ai                ← Ollama bio endpoint
com.mirador.config            ← Cross-cutting configuration (root level only)
```

Within a feature package, sub-packages are allowed when natural
(`customer.web`, `customer.domain`, `customer.repository`) but are not
mandatory for small features.

## Consequences

### Positive

- Related code lives together. Renaming / deleting a feature is a single
  package delete.
- ArchUnit tests can enforce "feature packages don't reach into each
  other except through a public API" — impossible to express cleanly
  with layered packages.
- Modular monolith on-ramp: if a feature needs to become a separate
  service, the package boundary is already the extraction line.
- Reads well on the package-info Javadocs.

### Negative

- Spring component scanning must be configured to walk all feature
  packages (default `@SpringBootApplication` on `com.mirador` is fine;
  just don't scatter `@ComponentScan(basePackages=...)`).
- Some shared utilities (e.g., generic JSON error response) live in
  `config` which is a slight violation of "one package per feature". We
  accept this — the alternative (a `common` package) has its own risks.

### Neutral

- Each feature package has a `package-info.java` summarising its purpose
  and external surface. This is enforced by Checkstyle's
  `JavadocPackage` module.

## Alternatives considered

### Alternative A — Layered (controller/service/repository/model)

Rejected: does not scale, buries business logic across four packages,
hostile to modular-monolith extraction.

### Alternative B — Hexagonal / Clean Architecture layers
(`domain/`, `application/`, `infrastructure/`)

Considered but rejected as premature. For a service this size the
ceremony (separate domain model types, mappers at every layer boundary)
outweighs the benefit. We can adopt it per-feature if one of them grows
enough to justify it.

## References

- `src/main/java/com/mirador/*/package-info.java` — per-package
  documentation showing the pattern in action.
- [Spring Boot docs — "Locating the Main Application Class"](https://docs.spring.io/spring-boot/docs/current/reference/html/using.html#using.structuring-your-code.locating-the-main-class) — recommends a single
  top-level package, consistent with our layout.
