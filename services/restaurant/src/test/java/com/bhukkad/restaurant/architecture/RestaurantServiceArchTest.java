package com.bhukkad.restaurant.architecture;

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
                .that().resideInAPackage("com.bhukkad.restaurant.domain.service..")
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
            "com.bhukkad.restaurant.api.controller.AdminRestaurantController",
            "com.bhukkad.restaurant.api.controller.MenuBulkController",
            "com.bhukkad.restaurant.api.controller.MenuCategoryCompatController",
            "com.bhukkad.restaurant.api.controller.MenuOpsController",
            "com.bhukkad.restaurant.api.controller.MenuVersionController",
            "com.bhukkad.restaurant.api.controller.PublicBrowseController",
            "com.bhukkad.restaurant.api.controller.RestaurantOwnerController");

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
