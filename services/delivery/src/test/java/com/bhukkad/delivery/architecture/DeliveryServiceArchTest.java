package com.bhukkad.delivery.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DeliveryServiceArchTest {
    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.delivery");

    @Test
    void domainDoesNotDependOnWeb() {
        noClasses().that().resideInAPackage("com.bhukkad.delivery.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void controllersAreInApiLayer() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.bhukkad.delivery.api..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        noClasses().that().resideInAPackage("com.bhukkad.delivery..")
                .should().dependOnClassesThat().resideInAnyPackage("com.bhukkad.serviceImpl..", "com.bhukkad.entity..")
                .check(SERVICE_CLASSES);
    }
}
