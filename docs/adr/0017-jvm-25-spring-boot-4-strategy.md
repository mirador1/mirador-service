# ADR-0017: Java 25 + Spring Boot 4 (bleeding-edge stack)

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

Mirador runs on **Java 25 (bytecode v69) + Spring Boot 4.0.5 GA +
Spring Framework 7**. At the time of writing, the typical "safe"
Spring Boot stack is Java 21 LTS + Spring Boot 3.x. Choosing the
bleeding edge has concrete costs that accumulated during this
project:

- **Tooling pins**: ASM 9.8+, SpotBugs 4.8.6+, PMD 7.20+, Checkstyle
  13.4+ — every bytecode-consuming library had to be uprated,
  documented in `pom.xml` comments with upgrade notes.
- **Transitive-dep gaps**: `tools.jackson.core` pinned to 3.1.1+ to
  close CVE GHSA-2m67-wjpj-xhg9 before the Spring Boot BOM caught up.
- **SB4 shims**: two placeholder classes under
  `src/main/java/org/springframework/boot/autoconfigure/` exist only
  to satisfy Spring AI's `@AutoConfiguration(after=...)` references
  pointing at SB3-era packages (documented in ADR archive).
- **Recurring small patches**: OpenTelemetry appender pinned to
  2.18.1-alpha because later -alpha versions assume OTel API 1.58+
  while SB4's BOM pins 1.55 (sign-off comment in `pom.xml`).

Despite the cost, this choice is deliberate — Mirador's purpose is to
**demonstrate** modern platform/JVM practices, and sitting two majors
behind the industry trajectory would miss the point.

## Decision

**Mirador tracks the Java GA + Spring Boot GA lines within one major
release of latest**, even when that means carrying short-lived
transitive-dep pins and shim classes.

Concrete commitments the stack leans on:

| Feature | Used for | JEP / Spring ref |
|---|---|---|
| Virtual threads (`Executors.newVirtualThreadPerTaskExecutor`) | Tomcat request threads, Kafka listener pool | JEP 444 (Java 21 GA) |
| ScopedValue (replaces ThreadLocal) | Per-request baggage (`RequestContext.REQUEST_ID`) | JEP 446 (Java 21 GA) |
| Unnamed pattern `catch (Type _)` | Drop-through exception handlers (Sonar S7467) | JEP 443 (preview 22 → final 25) |
| Pattern matching for switch w/ `when` | ProblemDetail exception mapper, cleanCveId | JEP 441 (Java 21 GA) |
| Records | All API DTOs + internal result types (~40 records) | JEP 395 (Java 16 GA) |
| Sealed hierarchies | Not yet — on the "next refactor" list | JEP 409 |
| `@Deprecated(since, forRemoval)` | JwtTokenProvider legacy overload | Java 9 |
| Spring Framework 7 native API versioning (`@RequestMapping(version="2.0+")`) | CustomerController v1/v2 endpoints | Spring 7 |
| Problem Details for HTTP APIs (RFC 7807) | `ApiExceptionHandler` | Spring 6+ |

## Alternatives considered

**Java 21 LTS only**:
- Covers 90 % of the JEPs listed above (ScopedValue, virtual threads,
  records, pattern matching).
- **Against**: loses JEP 443 unnamed pattern (the `_` catch) which is
  a visible demo win. Also falls behind on class-file format 65→69
  which blocks demonstrating the "always-upgraded" tooling chain.

**Spring Boot 3.5 LTS + Java 21**:
- Dependency tree is stable. Zero shim classes needed.
- **Against**: same "mainstream-two-years-ago" positioning. Would
  need to be re-invigorated every time a customer mentions Spring
  Boot 4.

**Quarkus + GraalVM native**:
- Different ecosystem; would pull Mirador away from the Spring world.
- **Against**: the GraalVM angle is already covered by the
  `native-build` schedule in `.gitlab-ci.yml`, which produces a
  native image of the SB4 app. Switching ecosystem would erase the
  ADRs on Spring AI, Keycloak, Micrometer, etc.

## Consequences

Positive:
- Showcases the full set of JDK/Spring innovations end users care
  about when they evaluate a modern platform.
- `/actuator/info` + `/actuator/quality` surface everything
  (`java.version=25`, `spring-boot.version=4.0.5 GA`,
  `jvm.bytecode-version=69`) in one panel that paints the value
  proposition.
- Consumers pick up the "what you can do now" upgrade path from
  reading the code, pom, and ADRs.

Negative:
- **Transitive-dep friction**: roughly once a month a library has to
  be pinned or shimmed. Accepted cost — documented in `pom.xml`
  comments each time.
- **CI image drift**: `maven:3.9.14-eclipse-temurin-25-noble` is a
  moving target. Pinning the Noble base digest was considered but
  would force an explicit bump every patch release. Keeping the
  floating tag and letting Renovate catch drift proved tolerable.
- **Learning curve** for contributors who last touched Spring Boot
  3.x. Mitigated by the `docs/reference/technologies.md` glossary
  that explains every JEP / Spring feature the code uses.

## References

- ADR-0009 — eclipse-temurin:25-jre runtime image
- ADR-0010 — OTLP push to Collector (uses virtual-thread-friendly
  exporters)
- `docs/reference/technologies.md` — 1100-line glossary of
  every dependency + JEP pattern in use
