package com.bhukkad.notification.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

class NotificationServiceArchTest {
    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.notification");

    @Test
    void domainDoesNotDependOnWeb() {
        noClasses().that().resideInAPackage("com.bhukkad.notification.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void controllersAreInApiLayer() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.bhukkad.notification.api..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        noClasses().that().resideInAPackage("com.bhukkad.notification..")
                .should().dependOnClassesThat().resideInAnyPackage("com.bhukkad.service..", "com.bhukkad.domain..")
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
