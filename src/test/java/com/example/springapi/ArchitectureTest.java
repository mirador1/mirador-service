package com.example.springapi;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
 * NOTE: ArchUnit 1.4.x uses ASM which does not yet support Java 25 bytecode
 * (class file version 69). These tests are skipped on Java 25+ and will be
 * re-enabled once ArchUnit ships an ASM version with Java 25 support.
 */
@DisabledIfSystemProperty(named = "java.version", matches = "2[5-9].*",
        disabledReason = "ArchUnit ASM does not support Java 25 bytecode yet")
class ArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.springapi");
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
        // All @KafkaListener classes must live in the messaging package.
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
                .should().resideInAPackage("..messaging..")
                .because("Kafka listeners must be grouped in the messaging package");
        rule.check(classes);
    }

    @Test
    void rest_controllers_should_reside_in_customer_or_auth_package() {
        // @RestController classes may only live in customer/ (domain) or auth/ (security).
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAnyPackage("..customer..", "..auth..")
                .because("REST controllers must be grouped in domain or auth packages");
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
}
