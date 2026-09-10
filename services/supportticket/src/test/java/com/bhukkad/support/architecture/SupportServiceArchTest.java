package com.bhukkad.support.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

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
 *   <li>G-14 write-ban: any class reachable here whose name looks like a
 *       cross-domain repository ({@code *Order*Repository}, {@code *User*Repository},
 *       {@code *GiftCard*Repository}, ... — including read-model copies this
 *       service may legitimately introduce later) must never see a
 *       {@code save*}/{@code delete*}/{@code remove*} call or a
 *       {@code @Modifying} query from supportticket code. Reads (find*, get*,
 *       count*, exists*) stay legal — that is the sanctioned read-model path;</li>
 *   <li>every Spring Data repository declared here must persist ONLY entities
 *       owned by this service (com.bhukkad.support.entity);</li>
 *   <li>domain persistence stays inside the repository package.</li>
 * </ol>
 *
 * <p>Defense in depth: docs/sql/grants-select-only.sql documents the matching
 * database-layer GRANT SELECT ONLY for the support role on foreign tables
 * (applied by the DBA, not auto-applied) — the same read-yes/write-no line,
 * enforced at the database layer.</p>
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

    /**
     * Simple-name fragments that mark a repository as belonging to a foreign
     * domain (R-08 evidence list: Order/User/GiftCard copies). Extends to the
     * other money-adjacent domains so a renamed copy cannot slip through.
     */
    private static final Set<String> CROSS_DOMAIN_NAME_FRAGMENTS = Set.of(
            "Order", "User", "GiftCard", "Wallet", "Customer", "Payment");

    /**
     * Matches the Spring Data write surface (and close cousins) — everything
     * that can INSERT/UPDATE/DELETE rows. Read surface (find*, get*, count*,
     * exists*, stream*) is deliberately NOT matched: reads are legal.
     */
    private static final DescribedPredicate<JavaCall<?>> WRITE_CALL_ON_CROSS_DOMAIN_REPOSITORY =
            new DescribedPredicate<>("write call (save*/delete*/remove*) on a "
                    + "cross-domain (order/user/gift-card/...) repository") {
                @Override
                public boolean test(JavaCall<?> call) {
                    if (!isCrossDomainRepository(call.getTargetOwner())) {
                        return false;
                    }
                    String method = call.getTarget().getName();
                    return method.startsWith("save")
                            || method.startsWith("delete")
                            || method.startsWith("remove");
                }
            };

    private static boolean isCrossDomainRepository(JavaClass owner) {
        String name = owner.getSimpleName();
        return name.endsWith("Repository")
                && !owner.getModifiers().contains(JavaModifier.ABSTRACT)
                && CROSS_DOMAIN_NAME_FRAGMENTS.stream().anyMatch(name::contains);
    }

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
    void noWritesViaCrossDomainRepositories() {
        // G-14 completion: even when a cross-domain read-model repository is
        // legitimately reintroduced (sanctioned: reads via find*, get*, count*, 
        // exists*), the write surface must stay unreachable from support code.
        // One violating call site fails the build before the DB grant would.
        noClasses().that().resideInAPackage("com.bhukkad.support..")
                .should().callMethodWhere(WRITE_CALL_ON_CROSS_DOMAIN_REPOSITORY)
                .because("supportticket reads foreign domains read-only: writes "
                        + "belong to the owning service (R-08, G-14, ADR-001)")
                .check(SERVICE_CLASSES);
    }

    @Test
    void noModifyingQueriesOnCrossDomainRepositories() {
        // The declaration-side mirror of the call-site ban above: a copied
        // cross-domain repository may carry derived reads, but no @Modifying
        // (INSERT/UPDATE/DELETE) query methods may be declared on it here.
        noMethods().that(declaredInCrossDomainRepository())
                .should().notBeAnnotatedWith(Modifying.class)
                .because("@Modifying queries are raw writes — forbidden on "
                        + "cross-domain repositories (R-08, G-14)")
                // The that()-set is intentionally EMPTY after ADR-001 (no
                // cross-domain repositories may exist); empty-should must not
                // report that success as a failure.
                .allowEmptyShould(true)
                .check(SERVICE_CLASSES);
    }

    private static DescribedPredicate<com.tngtech.archunit.core.domain.JavaMember> declaredInCrossDomainRepository() {
        return new DescribedPredicate<>("declared in a cross-domain repository") {
            @Override
            public boolean test(com.tngtech.archunit.core.domain.JavaMember member) {
                return isCrossDomainRepository(member.getOwner());
            }
        };
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
