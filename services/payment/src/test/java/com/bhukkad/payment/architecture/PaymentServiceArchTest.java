package com.bhukkad.payment.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class PaymentServiceArchTest {

    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.payment");

    @Test
    void domainDoesNotDependOnWebOrIdempotency() {
        noClasses().that().resideInAPackage("com.bhukkad.payment.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "jakarta.servlet..",
                        "com.bhukkad.common.idempotency..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void moneyPathSingleWriter_domainOnlyHasEntityOwners() {
        classes().that().haveSimpleNameEndingWith("Repository")
                .should().resideInAPackage("com.bhukkad.payment.domain..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void controllersAreInApiLayer() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.bhukkad.payment.api..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        noClasses().that().resideInAPackage("com.bhukkad.payment..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.serviceImpl..", "com.bhukkad.entity..",
                        "com.bhukkad.service..", "com.bhukkad.outbox..")
                .check(SERVICE_CLASSES);
    }
}
