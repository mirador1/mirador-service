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
    void controllers_should_not_access_repository_directly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .should().accessClassesThat().resideInAPackage("..repository..")
                .because("controllers must go through the service layer");
        rule.check(classes);
    }

    @Test
    void services_should_not_depend_on_controllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..controller..")
                .because("service layer must not know about the web layer");
        rule.check(classes);
    }

    @Test
    void repositories_should_not_depend_on_services_or_controllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..service..", "..controller..")
                .because("repository layer must be independent of upper layers");
        rule.check(classes);
    }

    @Test
    void kafka_listeners_should_reside_in_kafka_package() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
                .should().resideInAPackage("..kafka..")
                .because("Kafka listeners must be grouped in the kafka package");
        rule.check(classes);
    }

    @Test
    void rest_controllers_should_reside_in_controller_package() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAPackage("..controller..")
                .because("REST controllers must be grouped in the controller package");
        rule.check(classes);
    }
}
