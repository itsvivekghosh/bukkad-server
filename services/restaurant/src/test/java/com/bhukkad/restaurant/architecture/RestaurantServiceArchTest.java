package com.bhukkad.restaurant.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Restaurant-service boundaries (plan §10: ArchUnit per-service rules).
 */
class RestaurantServiceArchTest {

    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.restaurant");

    @Test
    void domainDoesNotDependOnWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bhukkad.restaurant.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "jakarta.servlet..");
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void serviceLayerDependsOnlyOnDomainAndCommon() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.bhukkad.restaurant.service..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.restaurant..",
                        "com.bhukkad.common..",
                        "java..",
                        "org.springframework..",
                        "org.slf4j..",
                        "com.fasterxml.jackson..",
                        "lombok..");
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void controllersAreThinAndInApiLayer() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.bhukkad.restaurant.api..");
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bhukkad.restaurant..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.serviceImpl..", "com.bhukkad.entity..", "com.bhukkad.outbox..");
        rule.check(SERVICE_CLASSES);
    }
}
