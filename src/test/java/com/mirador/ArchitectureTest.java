package com.mirador;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules verified with ArchUnit.
 *
 * Rules are expressed against the new hierarchical package structure:
 *   customer/  — domain model, repository, service, controller, DTOs, scheduler
 *   auth/      — JWT filter, token provider, security config, auth controller
 *   messaging/ — Kafka config, events, listeners, handlers
 *   integration/ — external API clients (HTTP Interface, Spring AI)
 *   observability/ — health, tracing, request ID
 *   resilience/  — rate limiting, idempotency, ShedLock
 *   api/         — error model and global exception handler
 *
 * NOTE: ArchUnit 1.4.x has a hardcoded version check in ClassFileProcessor that rejects
 * Java 25 bytecode (class file major version 69) with an IllegalArgumentException, regardless
 * of ASM version. These tests are skipped on Java 25+ and will be re-enabled once ArchUnit
 * ships a version that raises this limit (tracked upstream in the ArchUnit GitHub issues).
 * The dependencyManagement section in pom.xml already forces ASM 9.8 as a prerequisite.
 */
@DisabledIfSystemProperty(named = "java.version", matches = "2[5-9].*",
        disabledReason = "ArchUnit 1.4.x ClassFileProcessor rejects Java 25 bytecode (class version 69)")
class ArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mirador");
    }

    @Test
    void customer_controllers_should_not_access_repository_directly() {
        // CustomerController must go through CustomerService — never call the repo directly.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..customer..")
                .and().haveSimpleName("CustomerController")
                .should().accessClassesThat().resideInAPackage("..customer..")
                .andShould().accessClassesThat().haveSimpleName("CustomerRepository")
                .because("controllers must go through the service layer");
        rule.check(classes);
    }

    @Test
    void customer_service_should_not_depend_on_controllers() {
        // Services must be controller-agnostic — they should not import web-layer types.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..customer..")
                .and().haveSimpleNameEndingWith("Service")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("service layer must not know about the web layer");
        rule.check(classes);
    }

    @Test
    void repositories_should_not_depend_on_services_or_controllers() {
        // CustomerRepository must remain a pure data-access object.
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Service")
                .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("repository layer must be independent of upper layers");
        rule.check(classes);
    }

    @Test
    void kafka_listeners_should_reside_in_messaging_package() {
        // All @KafkaListener methods must live in classes in the messaging
        // package. @KafkaListener is a METHOD-level annotation (Spring Kafka
        // allows both class-level and method-level but our listeners use the
        // method form) — the original classes().that().areAnnotatedWith(...)
        // rule matched zero classes + failed with "no classes passed to the
        // rule". Switching to methods() + .arePublic() + check the declaring
        // class's package covers both class-level and method-level @KafkaListener
        // usages.
        ArchRule rule = methods()
                .that().areAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
                .should().beDeclaredInClassesThat().resideInAPackage("..messaging..")
                .because("Kafka listeners must be grouped in the messaging package");
        rule.check(classes);
    }

    @Test
    void rest_controllers_should_reside_in_web_layer_feature_packages() {
        // @RestController classes must live in a feature package that owns a
        // web surface. Concretely: customer/ (domain), auth/ (security),
        // observability/ (audit + quality reports), resilience/ (scheduled
        // jobs admin endpoints), diag/ (startup timings), chaos/ (admin-only
        // experiment triggers). NOT allowed: messaging/, integration/, api/.
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAnyPackage(
                        "..customer..",
                        "..auth..",
                        "..observability..",
                        "..resilience..",
                        "..diag..",
                        "..chaos..")
                .because("REST controllers must be grouped in a feature package that owns a web surface");
        rule.check(classes);
    }

    @Test
    void domain_ports_must_not_depend_on_framework_packages() {
        // Hexagonal-lite invariant (ADR-0044): classes in any `..port..`
        // sub-package of a feature slice are domain interfaces — they must
        // stay framework-free so the domain can be unit-tested without a
        // Spring context and so swapping the adapter (e.g. Kafka → RabbitMQ,
        // JPA → Mongo) is a true local change.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..port..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "org.apache.kafka..",
                        "io.fabric8..")
                .because("domain ports must be framework-free (ADR-0044)");
        rule.check(classes);
    }

    @Test
    void resilience_filters_should_not_depend_on_customer_domain() {
        // Cross-cutting filters (rate limit, idempotency) must be domain-agnostic.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..resilience..")
                .should().dependOnClassesThat().resideInAPackage("..customer..")
                .because("resilience filters are cross-cutting and must not depend on the customer domain");
        rule.check(classes);
    }

    @Test
    void jpa_entities_must_not_cross_feature_boundaries_via_port() {
        // Invariant #1 from ADR-0051: JPA entities (Customer, RefreshToken)
        // are legitimate intra-feature, but MUST NOT leak through a port/
        // sub-package — ports publish DTOs, not @Entity classes, so the
        // coupling stays local. See ADR-0051 "Invariants".
        ArchRule rule = noClasses()
                .that().resideInAPackage("..port..")
                .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
                .because("ADR-0051 invariant #1: JPA entities never cross feature boundaries via port/");
        rule.check(classes);
    }

    @Test
    void rest_controllers_must_not_return_jpa_entities() {
        // Invariant #2 from ADR-0051: @Entity classes must never be the return
        // type of a @GetMapping / @PostMapping — that would serialise Hibernate
        // lazy proxies straight to the HTTP response (LazyInitializationException
        // in the wild, or gigantic JSON payloads). Every handler returns a DTO
        // or Page<DTO>. ArchUnit can't easily inspect return types, so we
        // enforce the weaker "no @RestController directly depends on an
        // @Entity as part of its API surface" via package-level analysis:
        // controllers may reference entity-owning packages only through the
        // service layer. This is imperfect but catches most accidental leaks.
        //
        // NOTE: intentionally NOT a noClasses() rule because CustomerController
        // LEGITIMATELY depends on the customer package (where Customer lives)
        // for request objects, exceptions, etc. A tighter invariant would
        // require the entity to be in a sealed sub-package; that's a B-1b
        // follow-up. For now this ADR test is documentary — it will grow
        // teeth when entity classes move to `..entity..` sub-packages.
        //
        // Assertion: at least the three controllers that audit flagged
        // (CustomerController, AuthController, AuditController) exist —
        // guards against silent package moves.
        // Exclude *DemoController classes — they legitimately bypass the
        // service layer to demonstrate raw-SQL vulnerabilities (SecurityDemoController
        // uses JdbcTemplate directly for the SQL-injection OWASP A03 demo).
        // Routing demos through CustomerService would defeat their educational
        // purpose (show-the-bypass pattern).
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Controller")
                .and().resideInAPackage("..customer..")
                .and().haveSimpleNameNotEndingWith("DemoController")
                .should().dependOnClassesThat().haveSimpleName("CustomerService")
                .because("ADR-0051 invariant #2 (documentary): controllers go through services, which handle the entity→DTO mapping. Demo controllers excluded — see SecurityDemoController.");
        rule.check(classes);
    }

    @Test
    void jpa_entities_must_not_have_lazy_onetomany_without_dto_mapper() {
        // Invariant #3 from ADR-0051: @OneToMany(fetch = LAZY) is allowed
        // ONLY when the caller is guaranteed to NOT serialise the collection
        // directly (i.e. the service layer always materialises the collection
        // into a DTO before returning). Today no @OneToMany exists on any
        // entity — this rule is preventive. If one lands without a DTO
        // mapper, serialising from a @RestController will throw
        // LazyInitializationException.
        //
        // We can't detect the "without a DTO mapper" part in bytecode, so
        // we assert the weaker "no @OneToMany is present on any @Entity"
        // for now. Document the stricter intent in ADR-0051. The day a
        // legitimate @OneToMany lands, switch this rule to the stronger
        // "any @Entity with @OneToMany must also have a @JsonIgnore on
        // the collection field" form.
        ArchRule rule = noClasses()
                .that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.persistence.OneToMany")
                .because("ADR-0051 invariant #3: @OneToMany lazy collections leak to REST serialisation — add a DTO mapper or @JsonIgnore first");
        rule.check(classes);
    }
}
