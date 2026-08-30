package com.bhukkad.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Guardrails for the V62/V63 schema segregation and the microservice
 * extraction boundaries (Phase 0 of the modularization plan).
 *
 * <p>V62 split the identity model: the shared {@code users} table became a
 * slim registry and credentials/PII moved onto per-role tables owned by
 * {@code Admin}/{@code Customer}/{@code RestaurantOwner}/{@code DeliveryAgent},
 * with {@code AccountLookupService} as the single role-routed resolution
 * path. These rules keep that design from eroding and pin the first
 * package-level service boundaries.</p>
 */
class SegregationArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhukkad");
    }

    /**
     * The security layer sits below business services: it must never depend
     * on controllers, service implementations or infrastructure packages.
     * (It depends on entities, repositories, config and util only.)
     *
     * <p>Allowlist: {@code StompAuthChannelInterceptor} performs WebSocket
     * subscription authorization through {@code OrderLiveAccessService} — a
     * documented seam to dissolve when the live module extracts its own
     * authorization port.</p>
     */
    @Test
    void securityMustNotDependOnLayersAboveIt() {
        noClasses().that().resideInAPackage("..security..")
                .and().doNotHaveFullyQualifiedName("com.bhukkad.security.StompAuthChannelInterceptor")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..controller..", "..serviceImpl..", "..service..",
                        "..notification..", "..payment..", "..delivery..",
                        "..order..", "..restaurant..", "..wallet..", "..settlement..",
                        "..cart..", "..compliance..", "..fraud..", "..live..")
                .because("Security is a lower-level concern; role-routed identity resolution must not leak business services into it")
                .check(classes);
    }

    /**
     * The {@code admins} table is owned by the identity boundary. The Admin
     * entity must not sprawl across business domains — new consumers belong
     * in security/repository/config or the admin service.
     */
    @Test
    void adminEntityStaysWithinItsBoundary() {
        classes().that().resideInAPackage("..entity..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "jakarta..", "org.hibernate..",
                        "com.fasterxml.jackson..", "org.springframework..",
                        "com.bhukkad.entity..", "lombok..")
                .allowEmptyShould(true)
                .because("Entities may only depend on JDK/JPA/infrastructure types")
                .check(classes);

        noClasses().that().resideInAnyPackage(
                        "..order..", "..cart..", "..menu..", "..search..", "..feed..",
                        "..wallet..", "..settlement..", "..invoice..", "..promotion..",
                        "..recommendation..", "..referral..", "..survey..", "..churn..",
                        "..experiment..", "..featureflag..")
                .should().dependOnClassesThat().areAssignableTo(com.bhukkad.entity.Admin.class)
                .because("Admin accounts are owned by the identity boundary; business domains must resolve admins via AccountLookupService, not by depending on the entity")
                .check(classes);
    }

    /**
     * Contact PII (email/phone) lives on per-role tables since V62. Business
     * packages must not reach into role repositories directly for identity
     * resolution — they go through AccountLookupService, which routes by role
     * and keeps customer-first ordering. The admin service and auth service
     * are allowlisted as legitimate owners of role-scoped flows.
     */
    @Test
    void businessDomainsMustUseAccountLookupServiceForIdentityResolution() {
        noClasses().that().resideInAnyPackage(
                        "..order..", "..cart..", "..menu..", "..search..", "..feed..",
                        "..wallet..", "..settlement..", "..invoice..", "..promotion..",
                        "..recommendation..", "..referral..", "..survey..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..repository.AdminRepository",
                        "..repository.RestaurantOwnerRepository",
                        "..repository.DeliveryAgentRepository")
                .because("Role-scoped identity resolution crosses role tables — use AccountLookupService so the customer-first routing stays in one place")
                .check(classes);
    }
}
