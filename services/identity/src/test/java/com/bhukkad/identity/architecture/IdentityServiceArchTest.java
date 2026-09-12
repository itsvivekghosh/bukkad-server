package com.bhukkad.identity.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

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
                .that().resideInAPackage("com.bhukkad.identity.config..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.identity..",
                        "com.bhukkad.common..",
                        "com.nimbusds..",
                        "org.springframework.security..",
                        "org.springframework.stereotype..",
                        "org.springframework.boot..",
                        "org.springframework.context..",
                        "org.springframework.core..",
                        "org.springframework.web..",
                        "org.springframework.beans.factory..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "org.slf4j..",
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
                        "com.bhukkad.service..", "com.bhukkad.domain..",
                        "com.bhukkad.security..", "com.bhukkad.service..");
        rule.check(SERVICE_CLASSES);
    }

    // ------------------------------------------------------------------
    // G-4 (PRODUCTION-READINESS-AUDIT-GUIDE.md): transaction boundaries —
    // no @Transactional on controllers (V-05 proxy/self-invocation class).
    // Transaction boundaries belong in the service layer.
    // ------------------------------------------------------------------

    /**
     * KNOWN legacy offenders, frozen until an extraction batch moves their
     * transaction boundaries into the service layer. Delete an entry when the
     * annotations move; new controllers are blocked.
     */
    private static final Set<String> LEGACY_TRANSACTIONAL_CONTROLLERS = Set.of(
            "com.bhukkad.identity.api.controller.AdminUserInternalController",
            "com.bhukkad.identity.api.controller.ComplianceController",
            "com.bhukkad.identity.api.controller.CustomerAccountController",
            "com.bhukkad.identity.api.controller.CustomerSelfController");

    private static final DescribedPredicate<JavaClass> nonExemptControllers =
            new DescribedPredicate<>("controllers without a legacy @Transactional exemption") {
                @Override
                public boolean test(JavaClass input) {
                    return input.getSimpleName().endsWith("Controller")
                            && !LEGACY_TRANSACTIONAL_CONTROLLERS.contains(input.getFullName());
                }
            };

    @Test
    void noTransactionalOnControllerClasses() {
        noClasses().that(nonExemptControllers)
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noTransactionalOnControllerMethods() {
        noMethods().that().areDeclaredInClassesThat(nonExemptControllers)
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .check(SERVICE_CLASSES);
    }
}
