package com.bhukkad.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guardrails for the Bhukkad codebase.
 *
 * <p>These rules express the intended package layering and run in CI to
 * prevent regressions. They are calibrated to the codebase as it exists:
 * pre-existing, documented violations are allowlisted so the suite is both
 * green and meaningful — any NEW violation still fails the build.
 *
 * <p>Test classes are excluded from the import so controller tests (which
 * legitimately mock repositories) do not trip the production rules.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhukkad");
    }

    // ── Layer dependencies ──────────────────────────────────────────────────

    /**
     * Repositories may depend on entities, Spring Data types and the response
     * DTOs returned by projection queries (e.g. OrderRepository →
     * OrderSummaryResponse). Everything else — controllers, services, config,
     * security, eventing — is off-limits.
     */
    @Test
    void repositoriesMayOnlyDependOnEntitiesAndDto() {
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..controller..", "..service..", "..serviceImpl..", "..config..",
                        "..security..", "..logging..", "..metrics..", "..ratelimit..",
                        "..outbox..", "..cache..", "..event..", "..live..",
                        "..fraud..", "..invoice..", "..payment..", "..notification..",
                        "..delivery..", "..restaurant..", "..order..", "..zone..",
                        "..membership..", "..wallet..", "..promotion..", "..feed..",
                        "..timeline..", "..support..", "..referral..", "..settlement..",
                        "..inventory..", "..storage..")
                .because("Repositories are the persistence layer; they should only depend on entities, DTOs and Spring Data")
                .check(classes);
    }

    /**
     * Entities are domain primitives and must not reference any other layer.
     */
    @Test
    void entitiesMustNotDependOnAnyOtherLayer() {
        noClasses().that().resideInAPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..controller..", "..service..", "..serviceImpl..", "..repository..",
                        "..dto..", "..config..", "..security..", "..logging..",
                        "..metrics..", "..ratelimit..", "..outbox..", "..cache..",
                        "..event..", "..live..")
                .because("Entities are domain primitives; they must not reference any other layer")
                .check(classes);
    }

    /**
     * Services (interfaces and implementations) must never depend on controllers.
     */
    @Test
    void servicesMustNotDependOnControllers() {
        noClasses().that().resideInAnyPackage("..service..", "..serviceImpl..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..")
                .because("Services are below controllers in the dependency hierarchy")
                .check(classes);
    }

    /**
     * DTOs must not depend on controllers or repositories.
     */
    @Test
    void dtoMustNotDependOnControllersOrRepositories() {
        noClasses().that().resideInAPackage("..dto..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..repository..")
                .because("DTOs are API contracts and must stay independent of controllers and repositories")
                .check(classes);
    }

    // ── Controller → repository restriction ─────────────────────────────────

    /**
     * Controllers must depend on service interfaces — not on repositories or
     * service implementations. Two pre-existing controllers are allowlisted:
     * they perform trivial read-only lookups and predate the service layer.
     */
    @Test
    void controllersMustNotDependOnRepositoriesOrServiceImpls() {
        noClasses()
                .that().resideInAPackage("..controller..")
                .and().doNotHaveSimpleName("ServiceabilityController")
                .and().doNotHaveSimpleName("CuisineController")
                .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..serviceImpl..")
                .because("Controllers should depend on service interfaces, not on repositories or implementations directly")
                .check(classes);
    }
}