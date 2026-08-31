package com.bhukkad.identity.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Identity-service boundaries (plan §10: ArchUnit per-service rules).
 */
class IdentityServiceArchTest {

    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.identity");

    @Test
    void domainDoesNotDependOnWebOrSecurity() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bhukkad.identity.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "jakarta.servlet..",
                        "com.nimbusds..", "org.springframework.security..");
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void securityLayerIsIsolated() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.bhukkad.identity.security..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.identity..",
                        "com.nimbusds..",
                        "org.springframework.security..",
                        "org.springframework.stereotype..",
                        "org.springframework.boot.context.properties..",
                        "java..",
                        "lombok..");
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void controllersAreInApiLayer() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.bhukkad.identity.api..");
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bhukkad.identity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.serviceImpl..", "com.bhukkad.entity..",
                        "com.bhukkad.security..", "com.bhukkad.service..");
        rule.check(SERVICE_CLASSES);
    }
}
