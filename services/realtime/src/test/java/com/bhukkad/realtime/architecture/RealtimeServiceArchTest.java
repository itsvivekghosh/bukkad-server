package com.bhukkad.realtime.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Boundary guardrails for the realtime service, mirroring the order-service
 * pattern: controllers live in the {@code api} layer, consumers/services live
 * in the {@code service} layer (never scattered in the root package), and the
 * realtime service never reaches into monolith packages.
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
                .should().resideInAPackage("com.bhukkad.realtime.service..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        noClasses().that().resideInAPackage("com.bhukkad.realtime..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukbad.serviceImpl..", "com.bhukbad.entity..", "com.bhukad.dto..")
                .check(SERVICE_CLASSES);
    }
}
