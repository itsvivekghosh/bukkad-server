package com.bhukkad.realtime.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Boundary guardrails for the realtime service: controllers live in the
 * {@code api.controller} layer, consumers/services live in the
 * {@code domain.service.impl} layer, and the realtime service never reaches
 * into monolith packages.
 */
class RealtimeServiceArchTest {

    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.realtime");

    @Test
    void controllersAreInApiLayer() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.bhukkad.realtime.api..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void consumersAreInServicePackage() {
        classes().that().haveSimpleNameEndingWith("Consumer")
                .should().resideInAPackage("com.bhukkad.realtime.domain.service.impl..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        noClasses().that().resideInAPackage("com.bhukkad.realtime..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukbad.serviceImpl..", "com.bhukbad.entity..", "com.bhukkad.dto..")
                .check(SERVICE_CLASSES);
    }

    // ------------------------------------------------------------------
    // G-4 (PRODUCTION-READINESS-AUDIT-GUIDE.md): transaction boundaries —
    // no @Transactional on controllers (V-05 proxy/self-invocation class).
    // Transaction boundaries belong in the service layer.
    // ------------------------------------------------------------------

    @Test
    void noTransactionalOnControllerClasses() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noTransactionalOnControllerMethods() {
        noMethods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .check(SERVICE_CLASSES);
    }
}
