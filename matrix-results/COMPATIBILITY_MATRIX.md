# Compatibility Matrix — 2026-04-24

Generated from svc pipeline [#800](https://gitlab.com/mirador1/mirador-service/-/pipelines/2477553437)
on main SHA `4b0d960` (post-merge of !188 Grafana Cloud POC).

## Summary

| Cell | Status | Failure mode |
|---|---|---|
| SB3 + Java 17 | 🔴 FAIL | `release version 21 not supported` — Maven compiler plugin ignores `-Djava17` flag, tries to compile with `--release 21` |
| SB3 + Java 21 | 🔴 FAIL | Code uses Java 22+ unnamed variables (`_` patterns) which are preview-only in J21 |
| SB4 + Java 17 | 🔴 FAIL | Same `release version 21 not supported` issue as SB3+J17 |
| SB4 + Java 21 | 🔴 FAIL | 2 ArchTest violations (kafka_listeners package empty, customer controllers don't depend on CustomerService per ADR-0051) |
| SB4 + Java 25 (default) | 🟢 PASS | Main pipeline green — this is the canonical target |

All 4 compat jobs run with `allow_failure: true` (informational matrix, doesn't gate main). Per CLAUDE.md "Surgical fixes not allow_failure bypasses", `allow_failure` here is legitimate because it's NOT a hiding-the-bug shield — the matrix is genuinely informational about multi-version support. Still, each failure is a real regression worth tracking.

## Detail per cell

### SB3 + Java 17 (compat-sb3-java17, job 14078246700)

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile
(default-compile) on project mirador: Fatal error compiling: error: release version 21 not supported
```

**Root cause hypothesis** : the `-Djava17` Maven property is supposed to override the `<maven.compiler.release>` pom property to `17`, but the profile activation or property precedence doesn't actually lower it. The JDK installed on the runner IS 17 (else it wouldn't hit this error — it would be a different compatibility error).

**Suggested fix** :
1. Check pom.xml profile `-Pjava17` or property `${java17}` — is it actually setting `<maven.compiler.release>17</maven.compiler.release>` ?
2. If yes, check why Maven 3.14.1 plugin doesn't honor it (profile activation timing?)
3. Likely 1-line pom.xml fix or CI job env var.

Same fix applies to **SB4 + Java 17**.

### SB3 + Java 21 (compat-sb3-java21, job 14078246699)

```
[ERROR] /src/main/java/com/mirador/api/ApiExceptionHandler.java:[61,50]
  unnamed variables are a preview feature and are disabled by default.
[ERROR] /src/main/java/com/mirador/auth/AuthController.java:[126,44] (same)
[ERROR] /src/main/java/com/mirador/auth/JwtAuthenticationFilter.java:[128,22] (same)
[ERROR] /src/main/java/com/mirador/customer/RecentCustomerBuffer.java:[103,28] (same)
```

**Root cause** : code uses the `_` unnamed variable pattern (e.g. `catch (NumberFormatException _)`) which is a **preview** feature in Java 21 and **stable** only from Java 22+. Default target J25 compiles fine ; J21 without `--enable-preview` rejects.

**Suggested fix options** :
1. **Replace `_` with named variables** (`catch (NumberFormatException ignored)`) — 4 call sites, trivial. Makes the code J21-stable.
2. **Add `--enable-preview` to the J21 compat profile** — keeps `_` but adds preview dependency to J21 builds. Ugly for a compat matrix.
3. **Bump compat baseline to J22+** — abandon J21 support. Breaks the current matrix contract.

Recommend option 1.

### SB4 + Java 17 (compat-sb4-java17, job 14078246698)

Same root cause as SB3+J17 (`release version 21 not supported`).

### SB4 + Java 21 (compat-sb4-java21, job 14078246697)

```
[ERROR] ArchitectureTest.kafka_listeners_should_reside_in_messaging_package:87
  Rule 'classes that are annotated with @KafkaListener should reside in a package '..messaging..'...'
  failed to check any classes. This means either that no classes have been passed to the rule at
  all, or that no classes passed to the rule matched the `that()` clause.

[ERROR] ArchitectureTest.rest_controllers_must_not_return_jpa_entities:179
  Architecture Violation [Priority: MEDIUM] - Rule 'classes that have simple name ending with
  'Controller' and reside in a package '..customer..' should depend on classes that have simple
  name 'CustomerService', because ADR-0051 invariant #2 (documentary): controllers go through
  services, which handle the entity→DTO mapping' was violated (1 times)
```

**Root cause #1** — the ArchUnit rule expects at least one `@KafkaListener` class in the `..messaging..` package. Either :
- No classes are annotated anymore (removed in a refactor)
- The `@KafkaListener` moved to a different package

**Suggested fix** : add `.allowEmptyShould(true)` to the rule OR relocate the listener back to `..messaging..`.

**Root cause #2** — a Controller in `com.mirador.customer` doesn't depend on `CustomerService`. Likely the new `CustomerEnrichmentController` or `CustomerDiagnosticsController` uses the repo or enrichment service directly. ADR-0051 invariant is "documentary" per the message — intent was that controllers route through the main service.

**Suggested fix** : decide if ADR-0051 invariant #2 is still the intended pattern ; if yes, refactor the offending controller ; if no, relax the ArchTest to allow the exception.

## Tracking decisions

| Regression | Action | Owner |
|---|---|---|
| `_` unnamed variables (4 sites) | Replace with named ignored | Future session — low priority (J21 is informational only) |
| `release version 21 not supported` (J17 cells) | Fix Maven profile property precedence | Future session — blocker for J17 support claim |
| ArchTest `kafka_listeners_should_reside_in_messaging_package` | Either relocate listener OR `.allowEmptyShould(true)` | Future session |
| ArchTest `rest_controllers_must_not_return_jpa_entities` | Refactor controller OR amend ADR-0051 | Future session (needs ADR discussion) |

## Notes on scope

- Maven 3 vs 4 dimension skipped : the project's bundled `mvnw` = Maven 3. Maven 4 would need a separate `mvnw4` wrapper or `MAVEN_VERSION=4.x` env override on the CI job. Not currently supported.
- SB3 + Java 25 dimension skipped : Spring Boot 3 officially supports Java 17 + 21 only. Adding J25 to SB3 would require upstream SB3 backport or preview.
