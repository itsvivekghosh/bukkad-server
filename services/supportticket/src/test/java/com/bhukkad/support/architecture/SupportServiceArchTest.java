package com.bhukkad.support.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * R-08 enforcement (audit guide §7 R-08, ADR-001): supportticket may READ the
 * order/identity/payment domains it reacts to, but must NEVER write them.
 *
 * <p>The historical defect was copy-in entities: supportticket held its own
 * {@code Order}/{@code User}/{@code GiftCard} JPA repositories, giving it a
 * live write path into domains it does not own (single-writer drift, G-14).
 * The write path was removed with ADR-001 (supportticket is the dispute
 * system-of-record and emits {@code dispute_resolved} via its outbox; payment
 * performs the wallet credit). These rules keep it removed:</p>
 *
 * <ol>
 *   <li>no supportticket class may import another service's code at all
 *       (foreign entities can only enter as read models/DTOs or events);</li>
 *   <li>every Spring Data repository declared here must persist ONLY entities
 *       owned by this service (com.bhukkad.support.entity);</li>
 *   <li>domain persistence stays inside the repository package.</li>
 * </ol>
 *
 * <p>Defense in depth: docs/sql/grants-select-only.sql documents the matching
 * database-layer GRANT SELECT ONLY for the support role on foreign tables
 * (applied by the DBA, not auto-applied).</p>
 */
class SupportServiceArchTest {

    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.support");

    /** Foreign domains whose repositories must never reappear in this service. */
    private static final String[] FOREIGN_DOMAIN_PACKAGES = {
            "com.bhukkad.order..",
            "com.bhukkad.identity..",
            "com.bhukkad.payment..",
            "com.bhukkad.restaurant..",
            "com.bhukkad.delivery..",
            "com.bhukkad.notification..",
            "com.bhukkad.search.."
    };

    @Test
    void noCrossDomainEntityOrRepositoryCopies() {
        // R-08 root cause class: copy-in Order/User/GiftCard entities +
        // repositories. The service talks to foreign domains only through its
        // own read models (com.bhukkad.support..), platform-lib contracts
        // (com.bhukkad.common..), and the outbox/event channel.
        noClasses().that().resideInAPackage("com.bhukkad.support..")
                .should().dependOnClassesThat().resideInAnyPackage(FOREIGN_DOMAIN_PACKAGES)
                .because("supportticket must not reach into another domain's "
                        + "entities/repositories (R-08, ADR-001, G-14 single-writer)")
                .check(SERVICE_CLASSES);
    }

    @Test
    void repositoriesOnlyPersistOwnedEntities() {
        // Any repository interface declared here must reference entity types
        // from this service's owned entity package — a foreign entity type
        // parameter is a copy-in write path, even inside a local interface.
        ArchRule rule = classes().that().resideInAPackage("com.bhukkad.support.repository..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.support..",       // owned entities + enums
                        "com.bhukkad.common..",        // platform-lib value types
                        "java..",
                        "jakarta..",
                        "org.springframework.data..",
                        "org.springframework.stereotype..",
                        "org.springframework.transaction..")
                .because("supportticket repositories may only persist owned "
                        + "entities (R-08 read-model rule)");
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void entitiesLiveInOwnedEntityPackage() {
        // The dispute aggregate — the table ADR-001 assigns to supportticket —
        // stays in com.bhukkad.support.entity; nothing may shadow it elsewhere.
        classes().that().haveSimpleName("Dispute")
                .or().haveSimpleName("SupportTicket")
                .should().resideInAPackage("com.bhukkad.support.entity..")
                .check(SERVICE_CLASSES);
    }
}
