package com.bhukkad.order.architecture;

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

class OrderServiceArchTest {

    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.order");

    @Test
    void domainDoesNotDependOnWeb() {
        noClasses().that().resideInAPackage("com.bhukkad.order.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "jakarta.servlet..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void controllersAreInApiLayer() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.bhukkad.order.api..")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noMonolithDependencies() {
        noClasses().that().resideInAPackage("com.bhukkad.order..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.serviceImpl..", "com.bhukkad.entity..")
                .check(SERVICE_CLASSES);
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
            "com.bhukkad.order.api.AdminOrderInternalController",
            "com.bhukkad.order.api.OrderAdjunctController",
            "com.bhukkad.order.api.OrderOpsController");

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