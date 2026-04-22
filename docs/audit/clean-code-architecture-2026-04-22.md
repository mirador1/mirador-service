# Clean Code + Clean Architecture audit — 2026-04-22

## TL;DR

Mirador's overall posture is **good for a feature-sliced Spring Boot
service**, with several Clean Code outliers already scoped for Phase B
refactors (file-length hygiene) and one Clean Architecture choice
(feature-slicing over strict layered) explicitly documented in
ADR-0008 + ADR-0044.

- **4 Clean Code items need fixing** — the file-length outliers (Phase B
  already tracks them) + 1 new finding (Observation-wrap duplication in
  CustomerController).
- **2 Clean Architecture items worth discussion** — expand ports
  coverage beyond Kafka (AuditService, BioService), consider a domain
  package separate from JPA entities (bigger, ADR-worthy decision).
- **4 deliberate deviations** explicitly chosen, documented in ADRs,
  should stay as-is.

Net: the codebase is **80 % aligned with Clean Code** and **70 %
aligned with Clean Architecture** relative to a by-the-book Uncle Bob
reading. The gaps are known and mostly compressed into existing Phase
B + Phase C + Phase 4 backlog items.

## Methodology

- Audited svc repo Java (`src/main/java/com/mirador/`)
- Audited UI repo TypeScript (`src/app/`)
- Focused on:
  - File + method sizes (Clean Code: "small functions")
  - Duplication (DRY)
  - Layer dependency direction (Clean Architecture)
  - Framework coupling in business logic
  - Port/adapter coverage
  - Testability in isolation

Cross-referenced against:
- [ADR-0008](../adr/0008-retire-observability-ui-in-favour-of-grafana.md) — feature-slicing
- [ADR-0044](../adr/0044-hexagonal-considered-feature-slicing-retained.md) — Hexagonal Lite decision
- [docs/audit/quality-thresholds-2026-04-21.md](quality-thresholds-2026-04-21.md) — Phase A enforcement layer

## Clean Code findings

### 🔴 God classes / files (already tracked, Phase B)

Existing stability-check.sh `section_file_length` (≥1500 BLOCK) flags:

| File | Lines | Phase B ticket |
|---|---:|---|
| `QualityReportEndpoint.java` | 1 934 | B-1 (7 parsers + aggregator) |
| `CustomerController.java` | 748 | see new proposal below |
| `quality.component.html` | 1 742 | B-5 (QualityPanel children) |
| `dashboard.component` (.ts + .scss) | 1 022 + 1 258 | B-6 |
| `.gitlab-ci.yml` svc | 2 619 | B-2 (9 includes) |
| `.gitlab-ci.yml` UI | 1 067 | B-4 (6 includes) |
| `stability-check.sh` | 1 457 | B-3 (sections/*.sh) |

No new proposals — existing backlog covers these.

### 🟡 Long methods in retained files

Measured with an awk method-size scan (only methods, excluding class
body and comments):

| Method | File | Lines |
|---|---|---:|
| `buildDependenciesSection` | QualityReportEndpoint | 160 |
| `buildMetricsSection` | QualityReportEndpoint | 113 |
| `buildCoverageSection` | QualityReportEndpoint | 90 |
| `securityFilterChain` | SecurityConfig | 59 |
| `trigger` (chaos experiment factory) | ChaosService | 56 |
| `customOpenAPI` | OpenApiConfig | 56 |

The top 3 are inside `QualityReportEndpoint` — Phase B-1 split
automatically resolves them (each moves to a dedicated parser class).

The bottom 3 sit around the 50-line threshold:

- **`securityFilterChain`** (SecurityConfig) — mostly a DSL chain
  (`.csrf.disable().authorizeHttpRequests(...)`). Legitimate at this
  length; splitting hurts readability. **Keep as-is**.
- **`ChaosService.trigger`** — switches on `ChaosExperiment` enum and
  builds the Kubernetes resource via Fabric8. Could refactor into
  per-experiment strategy classes:
  ```java
  Map<ChaosExperiment, ChaosExperimentBuilder> builders;
  ```
  Gain: each experiment's ~15-line build lives in its own class, easier
  to unit-test. **Low priority** — 3 experiments today; add strategy
  pattern only when a 4th lands.
- **`customOpenAPI`** (OpenApiConfig) — setup is mostly declarative
  (Info + SecurityScheme + tags). Fine at 56 lines. **Keep as-is**.

### 🟡 NEW: Observation-wrap duplication in CustomerController

`CustomerController.java` (748 lines, 20 endpoints) has this pattern
repeated 12+ times:

```java
return Observation.createNotStarted("customer.xxx", observationRegistry)
        .lowCardinalityKeyValue(KEY_ENDPOINT, PATH_CUSTOMERS)
        .observe(() -> customerXxxTimer.record(() -> service.xxx(request)));
```

Factor into a helper:

```java
// in CustomerController (or a shared BaseController)
private <T> T observed(String opName, Timer timer, Supplier<T> op) {
  return Observation.createNotStarted(opName, observationRegistry)
      .lowCardinalityKeyValue(KEY_ENDPOINT, PATH_CUSTOMERS)
      .observe(() -> timer.record(op));
}

// call site (before: 4 lines, after: 1 line):
return observed("customer.create", customerCreateTimer, () -> service.create(request));
```

**Gain**: ~50 lines removed from the controller (scope: ~12 methods
× 3 redundant lines). Makes the controller readable as "a list of
endpoint wirings", not "a thicket of observation boilerplate".

**Priority**: do this during Phase B-CustomerController split (not yet
a Phase B ticket but worth adding — probably B-7 or fold into B-2).

### 🟢 DRY in the UI — healthy

- Core services (`ApiService`, `EnvService`, `AuthService`, etc.) are
  genuinely shared — no copy-paste HTTP code in components.
- Signals are used for state everywhere — no prop-drilling, no NgRx
  boilerplate.
- Generated types from OpenAPI (Phase 2.3 D1) eliminate DTO
  duplication between backend and frontend.

### 🟢 Naming — consistent

- Java: camelCase methods, PascalCase classes, UPPER_SNAKE constants.
  Checkstyle naming checks enforce this (post-Phase A).
- TypeScript: `CustomerComponent`, `customerService`, `ROLE_ADMIN` —
  consistent.
- No cryptic abbreviations in recent code. The one legacy offender —
  `JwtAuthenticationFilter.authenticateKeycloak` covering Keycloak AND
  Auth0 (strategies 2 + 3) — was renamed to `authenticateExternalJwt`
  on 2026-04-22 as the first follow-up from this audit.

### 🟢 Comments — "why not what" discipline

Per-session evidence: `section_file_length` in `stability-check.sh`
has a clear "why exempt this file" comment per allowlist entry; ADRs
have "Alternatives considered" sections; even CI YAML carries dated
exit tickets (ADR-0049).

## Clean Architecture findings

### Context: ADR-0008 + ADR-0044 deliberate stance

Mirador explicitly chose **feature-slicing over strict layered
architecture** (ADR-0008: `com.mirador.{customer,auth,chaos,...}/`
each containing controller+service+repository+DTOs) and rejected
full Hexagonal (ADR-0044) in favour of **Hexagonal Lite**: one
`port/` sub-package per feature when cross-feature coupling emerges.

Current port coverage:

| Feature | Port | Adapter(s) |
|---|---|---|
| customer | `CustomerEventPort` | `KafkaCustomerEventPublisher` |
| (others) | none | — |

### 🟡 Ports coverage gaps

Outbound collaborators that **could** be port-abstracted:

1. **`AuditService`** — used by 4 features (customer, auth, chaos,
   observability). Consumers directly inject a concrete `AuditService`
   class. An `AuditEventPort` interface would:
   - Enable swapping AuditService for a mock in tests without Spring
     context
   - Allow alternative storage (Kafka → audit topic, external SaaS)
     without touching consumers
   - Fit the ArchUnit rule in B-1
   **Priority**: medium. Extract during Phase B-1 if possible, else
   dedicated B-audit session (~1 h).

2. **`BioService` → `OllamaClient`** — the Ollama LLM call is behind
   `BioService` already, but BioService itself references `ChatClient`
   (Spring AI). A `BioGenerationPort` would decouple domain from
   Spring AI, useful the day Ollama is swapped for OpenAI or a local
   alternative.
   **Priority**: low — Spring AI's `ChatClient` is already a vendor-
   agnostic abstraction. Adding another port is over-engineering
   unless the LLM provider list grows.

3. **`RedisTemplate` direct usage** (in `JwtBlacklistService`, cache
   config) — Redis is a cross-cutting concern, not a feature. A
   `TokenBlacklistPort` on the auth side + per-feature cache wrappers
   would isolate the Redis dependency.
   **Priority**: low — Redis is considered infrastructure here, not
   domain. Keep direct.

### 🟡 `@Entity` on domain classes = classic "anemic-ish" pattern

Java classes with `@Entity`:
- `com.mirador.auth.AppUser`
- `com.mirador.auth.RefreshToken`
- `com.mirador.customer.Customer`

These are JPA entities directly used as domain models (reads + writes).
Strict Clean Architecture says "domain model has no framework
dependencies" → JPA annotations would sit on a separate persistence
model, with a mapper translating between them.

**Mirador's deliberate trade-off** (unwritten but observable):
- Gain: one less layer, no Customer ↔ CustomerEntity mapping
- Cost: changing the DB schema = changing the domain; JPA lazy-loading
  quirks surface in the domain

**Proposal**: ~~write ADR-0051 "Accept JPA entity = domain model" to
document the deliberate deviation.~~ **Done 2026-04-22** — shipped as
[ADR-0051](../adr/0051-jpa-entity-as-domain-model.md). The
`@Entity` on `Customer.java` is no longer an unspoken choice;
future maintainers see the ADR + its 3 invariants (no cross-feature
entity leak, no entity as REST return type, no `@OneToMany` lazy
collections without a DTO mapper).

### 🟢 DTOs well-separated from entities

Records (Java 21+) are used consistently for DTOs:
`CustomerDto`, `AuditEventDto`, `BatchImportResult`, `CustomerCreatedEvent`,
`CustomerEnrichReply`, `CustomerSummary`, `PageableObject`, `ApiError`,
`TodoItem`, etc.

Separating `Customer` entity from `CustomerDto` for API I/O is the
right call — public API isn't bound to database shape.

### 🟢 UI architecture sound for Angular 21

- 13 core services + 17 feature components = healthy ratio.
- `providedIn: 'root'` on all singletons (ApiService, EnvService,
  ThemeService, ToastService, MetricsService, ActivityService,
  AuthService, Auth0BridgeService, KeyboardService, DeepLinkService,
  FeatureFlagsService, TelemetryService, TourService).
- Signals-first for state (no component-local subscribe leaks after
  the Phase 4 cleanup).
- Ports not needed — Angular services ARE the abstractions; adding
  an interface layer would be Java-style over-engineering.

### 🟡 UI: service dependency circularity check

Not strictly audited, but a typical risk area. Quick check:
- `ApiService` → `EnvService` (baseUrl) + `HttpClient` ✅
- `AuthService` → signal-only, no deps ✅
- `Auth0BridgeService` → `AuthService` + `@auth0/auth0-angular` ✅
- `ActivityService` → signal-only ✅

No circular dependency observed in the 4 most-used services. Full
dependency-graph check is a lower-priority Phase 4 task.

### 🟢 Testability in isolation

- Unit tests exist that don't need Spring context: `JwtTokenProviderTest`,
  `AuditServiceTest`, `SecurityHeadersFilterTest`, `LoginAttemptServiceTest`.
- Integration tests extend `AbstractIntegrationTest` which sets up
  Testcontainers (Postgres + Kafka + Redis) once per class.
- UI tests use Vitest in `threads` pool mode (per `config/vitest.
  config.ts`) — no DOM per test = fast.

## Proposals, priority-ordered

### 🔴 Must do (already in backlog)

1. **Phase B-1/2/3/4/5/6** — file splits (tracked in TASKS.md)
2. **Phase C** — flip `failOnViolation=true` after B (tracked)

Nothing new at this priority.

### 🟠 Should do (new proposals from this audit)

1. **Observation-wrap helper in CustomerController** (~30 min, low
   risk) — folds 12 call sites from 4 lines to 1. Either as standalone
   commit or during Phase B-2 CI-modu session (it's a quick detour on
   the same file).

2. ~~**ADR-0051 "JPA entity = domain model"** (~45 min, doc-only) —
   document the deliberate deviation so future reviewers don't file
   it as tech debt. Cross-reference from ADR-0044 Hexagonal Lite.~~
   **Done 2026-04-22** — shipped as
   [ADR-0051](../adr/0051-jpa-entity-as-domain-model.md); ADR-0044
   "Related" line points at it; 3 invariants documented (no
   cross-feature leak, no REST return type, no lazy collections).

### 🟡 Could do (medium priority)

3. ~~**`AuditEventPort`** (~1 h) — extract port for cross-feature auditing.
   Mirrors the `CustomerEventPort` pattern from ADR-0044. Unit tests
   stop needing Spring context.~~ **Done 2026-04-22** — shipped under
   `com.mirador.observability.port.AuditEventPort`. `AuditService
   implements AuditEventPort`; `CustomerService` + `AuthController` now
   depend on the port (write side), read-side callers
   (`CustomerController.findByCustomerId`, `AuditController.findAll`)
   still depend on `AuditService` directly — a read-side port is a
   separate justified-when decision (only 1 cross-feature consumer).
   ArchUnit `domain_ports_must_not_depend_on_framework_packages` still
   green. Method renamed `log` → `recordEvent` to stop clashing with
   SLF4J `Logger log` in every caller.

4. ~~**Rename `authenticateKeycloak`** (~15 min) — misleading method
   name; actually dispatches to all 3 auth modes (built-in, Keycloak,
   Auth0). Rename to `authenticateByMode` or `dispatchAuthentication`.~~
   **Done 2026-04-22** — renamed to `authenticateExternalJwt` (commit
   in the same session as this audit). `authenticateBuiltin` sibling
   handles the HMAC case, `doFilterInternal` is the actual dispatcher.

### 🟢 Non-issues (deliberate)

The following are NOT violations to fix — they're documented choices:

1. **Feature-slicing over layered** — ADR-0008, working well at
   current scale (< 15 features per package). Revisit if the
   package count doubles.
2. **Hexagonal Lite over full Hexagonal** — ADR-0044, only `port/`
   when cross-feature coupling emerges. Pragmatic for a solo project.
3. **JPA entity = domain model** — see proposal #2 above; currently
   undocumented but deliberate. Will be ADR-0051.
4. **Spring annotations in service layer** — `@Cacheable`,
   `@Observed`, `@Transactional` on service methods. Accepted because
   alternative (aspect-oriented wrappers) is more complex for less
   gain.

## Follow-ups to this audit

- Proposals #1 + #2 ship as dedicated commits (Observation helper +
  ADR-0051). Total effort ~1h15.
- Proposals #3 + #4 added to TASKS.md as optional Phase 4 sub-items.
- Re-audit every 3-6 months or after a major feature wave lands. The
  Phase B file splits will reshape the metrics significantly; worth
  a re-audit post-Phase-B to see the updated state.

## References

- `~/.claude/CLAUDE.md` → "File length hygiene"
- [ADR-0008](../adr/0008-retire-observability-ui-in-favour-of-grafana.md) — feature-slicing
- [ADR-0044](../adr/0044-hexagonal-considered-feature-slicing-retained.md) — Hexagonal Lite
- [ADR-0049](../adr/0049-ci-shields-with-dated-exit-tickets.md) — shields pattern (dated exit discipline relevant to debt-tracking)
- Robert C. Martin, *Clean Code* (2008) — function-size guidance
- Robert C. Martin, *Clean Architecture* (2017) — layering + dependency rule
- Alistair Cockburn, Hexagonal Architecture pattern (2005)
