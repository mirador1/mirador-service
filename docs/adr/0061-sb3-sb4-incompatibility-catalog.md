# ADR-0061 : SB3 / SB4 incompatibility catalog

**Status** : Living catalog — updated whenever a new SB3/SB4 incompat surfaces.
**Date** : 2026-04-25
**Related** : ADR-0060 (SB3 = prod-grade target).

## Purpose

Single reference for all incompatibilities between Spring Boot 3.x (compat
target) and Spring Boot 4.x (default target). Each entry lists the symptom,
root cause, the fix mechanism (overlay/pin/exclusion), and current status.

The goal : when a new SB3-cell test failure appears, this catalog tells the
next session WHERE to look + WHICH mechanism to apply, instead of re-discovering
the same Spring/Java/library API divergence from scratch.

## Conventions

- **Mechanism** :
  - `OVERLAY` = file copy under `src/main/java-overlays/sb3/` (or test/)
  - `BOM PIN` = explicit `<dependencyManagement>` version pin in SB3 profile
  - `EXCLUDE` = maven-antrun fileset exclude (file removed from merged-sources)
  - `THROWS` = main code adds `throws Exception` (no-op in SB4, required in SB3)
- **Status** :
  - ✅ FIXED = wave shipped, compat-sb3-* doesn't fail on this anymore
  - 🔧 PARTIAL = mechanism applied but not complete (needs follow-up)
  - 🔴 PENDING = identified but not yet fixed
  - ⏭ DEFERRED = identified, fix deferred to dedicated session (effort > 1 day)

---

## Entry 1 : Spring 7 native API versioning (`@GetMapping(version=)`)

| Field | Value |
|---|---|
| **Symptom** | `cannot find symbol method version()` in SB3 compile |
| **Root cause** | `@GetMapping(version="1.0")` is a Spring Framework 7 feature. SB3 uses Spring Framework 6.x which doesn't have it. |
| **Mechanism** | `OVERLAY` : `src/main/java-overlays/sb3/com/mirador/customer/CustomerController.java` does manual `@RequestHeader("X-API-Version")` dispatch via a wrapper Spring entry point + 2 test-callable helper methods (`getAll(Pageable, String)` + `getAllV2(Pageable, String)` matching main's signatures). |
| **Status** | ✅ FIXED — svc 1.0.55 wave 6 |
| **Files** | `src/main/java-overlays/sb3/com/mirador/customer/CustomerController.java` |

## Entry 2 : Jackson V2 (`com.fasterxml.jackson.*`) ↔ V3 (`tools.jackson.*`)

| Field | Value |
|---|---|
| **Symptom** | `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs` (when V3 init runs but V2 annotations missing) OR `NoClassDefFoundError: tools.jackson.databind.ObjectMapper` (when main code imports V3 but only V2 on classpath). |
| **Root cause** | Jackson 3.x moved root package from `com.fasterxml.jackson.*` to `tools.jackson.*` + made exceptions unchecked (`JacksonException extends RuntimeException` instead of `JsonProcessingException extends IOException`). SB 3.4.x ships V2 only ; SB 4.0+ ships V3 (with V2 still available via opt-in dep). |
| **Mechanism** | `OVERLAY` for source files using `tools.jackson.*` imports. `BOM PIN` for transitive Jackson deps. |
| **Status** | 🔧 PARTIAL — RecentCustomerBuffer overlay shipped (wave 7). KafkaConfig + Spring transitive Jackson V3 pulls still leak via `spring-boot-jackson:4.0.5` (transitive of `spring-boot-hateoas`, `springdoc-openapi-starter-common`, possibly others). Each pin reveals another transitive dep. |
| **Files** | `src/main/java-overlays/sb3/com/mirador/customer/RecentCustomerBuffer.java` + test overlay. BOM pins for `spring-boot-starter-jackson` + `spring-boot-starter-hateoas` (insufficient — see DEFERRED below). |
| **Deferred work** | Identify ALL SB4 transitive deps that pull `spring-boot-jackson:4.0.5` and pin them to SB3 versions OR add `<exclusions>` blocks. Estimated 1-2 days (springdoc, hateoas, possibly more). |

## Entry 3 : Jackson V3 vs V2 — class-level differences (audit)

| Aspect | V2 | V3 | Migration impact |
|---|---|---|---|
| **Root package** | `com.fasterxml.jackson.{core,databind,annotation}` | `tools.jackson.{core,databind,annotation}` | All imports change |
| **Maven coords** | `com.fasterxml.jackson.core:jackson-databind:2.x` | `tools.jackson.core:jackson-databind:3.x` | Build config change |
| **Exceptions** | `JsonProcessingException` (checked, `extends IOException`) | `JacksonException` (unchecked, `extends RuntimeException`) | Catch blocks change |
| **Construction** | `new ObjectMapper()` mutable, `mapper.configure(SerializationFeature.X, true)` | `JsonMapper.builder().enable(X).build()` immutable builder | Bean wiring + Spring config differs |
| **Config features** | `MapperFeature`, `SerializationFeature` etc. | Same names, reorganised under `tools.jackson.databind.cfg` | Mostly transparent |
| **Modules** | `mapper.registerModule(new JavaTimeModule())` | `JsonMapper.builder().addModule(...).build()` | Bean configuration change |
| **Tree model** | `JsonNode` → `com.fasterxml.jackson.databind.JsonNode` | `JsonNode` → `tools.jackson.databind.JsonNode` (same methods) | Imports change only |
| **Java baseline** | Java 8+ | **Java 17+** | None for our SB3+J21 / J17 cells |
| **Performance** | baseline | ~10-15% faster (codegen) | Not measurable for our usage |

## Entry 4 : Spring Kafka V2 vs V3 serializer classes

| Field | Value |
|---|---|
| **Symptom** | `NoClassDefFoundError: tools.jackson.databind.json.JsonMapper$Builder` and `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs` raised from `KafkaTemplate` constructor in KafkaConfigTest. |
| **Root cause** | Spring Kafka 4.0 ships V3-aware classes `JacksonJsonSerializer` / `JacksonJsonDeserializer` AND its internal `JsonKafkaHeaderMapper` hard-references Jackson V3 (`tools.jackson.databind.json.JsonMapper`) inside its constructor. Even building a basic `new KafkaTemplate(pf)` triggers V3 init via `MessagingMessageConverter` → `JsonKafkaHeaderMapper`. SB3 ships only Jackson V2, so the V3 init crashes at class-load time. The legacy `JsonSerializer` / `JsonDeserializer` (no `Jackson` prefix) exist on both Spring Kafka 3.x and 4.x and are V2-based. |
| **Mechanism** | `BOM PIN` Spring Kafka 3.3.4 (last 3.x release on the SB 3.4.x line) in the SB3 profile + `OVERLAY` swap of `JacksonJsonSerializer` → `JsonSerializer` (and Deserializer counterpart) in both main and test files. SK 3.3.4 has no V3 references in `JsonKafkaHeaderMapper`, so the V3 chain is gone entirely. |
| **Status** | ✅ FIXED — svc 1.0.57 wave 8 |
| **Files** | `src/main/java-overlays/sb3/com/mirador/messaging/KafkaConfig.java`, `src/test/java-overlays/sb3/com/mirador/messaging/KafkaConfigTest.java`, pom.xml SB3 profile pins for `spring-kafka` + `spring-kafka-test` to 3.3.4. |

## Entry 5 : Spring Boot 4 `@GetMapping(version=)` rolled across `getAll` / `getAllV2`

| Field | Value |
|---|---|
| **Symptom** | `CustomerControllerTest` calls `controller.getAll(Pageable, String)` and `controller.getAllV2(Pageable, String)` directly ; SB3 overlay had `getAll(String, Pageable, String)` (3 args including header). |
| **Root cause** | SB4 native versioning lets main define 2 separate `@GetMapping(version="1.0")` + `@GetMapping(version="2.0+")` methods with same path. SB3 needs single `@GetMapping` + manual header dispatch ; the test was written against SB4's two-method signatures. |
| **Mechanism** | `OVERLAY` : SB3 overlay's `getAllDispatcher` (Spring entry, reads header) delegates to PUBLIC helper methods `getAll(Pageable, String)` and `getAllV2(Pageable, String)` matching main's signatures. Helpers are not `@GetMapping`-annotated so no Spring routing conflict ; tests can call them directly. |
| **Status** | ✅ FIXED — svc 1.0.55 wave 6 |

## Entry 6 : SB3 `HttpSecurity.securityFilterChain()` requires `throws Exception`

| Field | Value |
|---|---|
| **Symptom** | `unreported exception java.lang.Exception; must be caught or declared to be thrown` in SB3 compile of `SecurityConfig.java`. |
| **Root cause** | Spring Boot 3's `HttpSecurity` DSL methods (`csrf`, `cors`, `authorizeHttpRequests`, etc.) declare `throws Exception` ; SB4 dropped the throws. |
| **Mechanism** | `THROWS` : add `throws Exception` to the `securityFilterChain` method signature in MAIN code. No-op in SB4 (stricter signature, never thrown) ; required for SB3 compile. |
| **Status** | ✅ FIXED — svc 1.0.52 wave 3 |
| **Files** | `src/main/java/com/mirador/auth/SecurityConfig.java` |

## Entry 7 : SB3 `spring-boot-test-autoconfigure` package layout

| Field | Value |
|---|---|
| **Symptom** | `cannot find symbol class AutoConfigureMockMvc` in SB3 (the SB4 import path doesn't exist in SB3). |
| **Root cause** | SB4 moved `@AutoConfigureMockMvc` from `org.springframework.boot.test.autoconfigure.web.servlet` (SB3) to `org.springframework.boot.webmvc.test.autoconfigure` (SB4). Test files import the SB4 path. |
| **Mechanism** | `OVERLAY` (SHIM) : `src/test/java-overlays/sb3/org/springframework/boot/webmvc/test/autoconfigure/AutoConfigureMockMvc.java` provides the SB4 path as a meta-annotation that delegates to the SB3 location. Combined with `BOM PIN` for `spring-boot-test-autoconfigure` (forces SB3 version on classpath so the SB3 location actually exists). |
| **Status** | ✅ FIXED — svc 1.0.53 wave 4 |

## Entry 8 : SB4-only `RestTestClient`

| Field | Value |
|---|---|
| **Symptom** | `cannot find symbol class RestTestClient` in SB3 compile. |
| **Root cause** | `org.springframework.test.web.servlet.client.RestTestClient` is a Spring Framework 7 class (SB4 only). |
| **Mechanism** | `EXCLUDE` : maven-antrun fileset exclude removes `CustomerRestClientITest.java` from merged-sources/test in SB3 mode. |
| **Status** | ✅ FIXED — svc 1.0.53 wave 4 |
| **Files** | pom.xml SB3 antrun exclusion list |

## Entry 9 : `static final String PATH_CUSTOMERS` shared constant

| Field | Value |
|---|---|
| **Symptom** | `cannot find symbol variable PATH_CUSTOMERS` referenced by `CustomerEnrichmentController` + `CustomerDiagnosticsController` from main when they look at SB3-overlay `CustomerController`. |
| **Root cause** | Refactor extracted controllers from main `CustomerController` 2026-04-22, the shared `PATH_CUSTOMERS` constant was added to main but the SB3 overlay didn't have it. |
| **Mechanism** | Add the field to the SB3 overlay too (mirror main). |
| **Status** | ✅ FIXED — svc 1.0.52 wave 3 |

## Entry 10 : Java 25 preview APIs (`ScopedValue`)

| Field | Value |
|---|---|
| **Symptom** | `ScopedValue is a preview API and is disabled by default` in J21/J17 compile. |
| **Root cause** | `java.lang.ScopedValue` is a J25 preview feature. J21/J17 reject preview APIs without `--enable-preview`. |
| **Mechanism** | `OVERLAY` : `src/main/java-overlays/pre-java25/...` files replace `ScopedValue` with `InheritableThreadLocal` (functionally equivalent for single-thread propagation, available since Java 1.0). |
| **Status** | ✅ FIXED — pre-existing wave (svc 1.0.x earlier) |
| **Files** | `RequestContext.java`, `RequestIdFilter.java`, `TraceService.java` (+ test) overlays under `pre-java25/` |

## Entry 11 : Java 21 preview unnamed variable `_`

| Field | Value |
|---|---|
| **Symptom** | `unnamed variables are a preview feature and are disabled by default` |
| **Root cause** | `catch (X _)`, `try (var _ = ...)`, `case X _ ->` are J21 preview ; stable in J22+. |
| **Mechanism** | maven-antrun `<replaceregexp>` in compat profile rewrites `_` → `ignored` (or `_ignoredRes` for try-with-resources). |
| **Status** | ✅ FIXED — svc 1.0.49 wave 1 + 1.0.50 (switch case) |

## Entry 12 : J21+ APIs (`Executors.newVirtualThreadPerTaskExecutor`, `List.getLast`/`getFirst`, ExecutorService try-with-resources)

| Field | Value |
|---|---|
| **Symptom** | `cannot find symbol method newVirtualThreadPerTaskExecutor` (J21+) ; `cannot find symbol method getLast/getFirst` (J21+) ; `try-with-resources not applicable to ExecutorService` (J19+ for autocloseable). |
| **Root cause** | These APIs are J21+ ; J17 doesn't have them. |
| **Mechanism** | `OVERLAY` (java17) : `AggregationService.java` (platform threads + try/finally), `AggregationServicePropertyTest.java` (same). For inline single-line uses : `page.get(page.size()-1)` instead of `page.getLast()` ; `findAll().get(0)` instead of `findAll().getFirst()`. |
| **Status** | ✅ FIXED — svc 1.0.51 wave 2 + 1.0.52 wave 3 |

## Entry 13 : Checkstyle 13.x J17 incompatibility

| Field | Value |
|---|---|
| **Symptom** | `UnsupportedClassVersionError: com/puppycrawl/tools/checkstyle/api/AuditListener has been compiled by ... class file version 65.0` (J21 bytecode) |
| **Root cause** | Checkstyle 13.x is compiled with Java 21 bytecode ; J17 runtime can't load it. |
| **Mechanism** | `BOM PIN` : pin `checkstyle.version=10.21.0` in `java17` profile (still supports J11+). Default profile keeps 13.4.0. |
| **Status** | ✅ FIXED — svc 1.0.54 wave 5 |

## Entry 14 : SB3 antrun missing `pre-java25` + `java17` test overlay copies

| Field | Value |
|---|---|
| **Symptom** | SB3+J17 / SB3+J21 compile errors from tests using `ScopedValue` or J21+ try-with-resources, even though pre-java25 / java17 overlays exist. |
| **Root cause** | The compat profile's antrun config copied test overlays ; the SB3 profile's antrun was missing those copy steps. |
| **Mechanism** | Add `<copy>` antrun steps to SB3 antrun for `pre-java25` (always) + `java17` (only if `-Djava17`). |
| **Status** | ✅ FIXED — svc 1.0.53 wave 4 + 1.0.54 wave 5 |

---

## Summary of mechanisms by SB3-only complexity added

| Mechanism | Count | Cost |
|---|---|---|
| `OVERLAY` files (main) | 6 | Maintenance burden — refactors of main require corresponding overlay updates |
| `OVERLAY` files (test) | 3 | Same |
| `BOM PIN` (dependencyManagement) | 7+ | Per-pin verification needed (does the artifact exist in SB3 version?) |
| `EXCLUDE` (antrun) | 1 (RestTestClient) | Loses test coverage in SB3 mode |
| `THROWS Exception` in main | 1 | Negligible — stricter signature |
| antrun overlay copy steps | 3 (sb3 +overlays) | Negligible |

## Open questions

1. **Is SB3 prod-grade really required ?** ADR-0060 said yes. If the
   ongoing maintenance cost (per Entry 2 alone) exceeds the value of
   supporting SB3 customers, ADR-0060 should be revisited.
2. **What's the exhaustive list of V3 Jackson transitive sources ?**
   Currently identified : `spring-boot-hateoas`, `springdoc-openapi-starter-common`.
   Likely more lurking. A `mvn dependency:tree -Dsb3 | grep tools.jackson`
   audit would catalog them.
3. **Is there a Spring Boot 3.x → 4.x migration tool ?** Spring publishes
   migration guides. Worth checking for an automated rewrite tool that could
   replace overlay files with mechanical transformations.
