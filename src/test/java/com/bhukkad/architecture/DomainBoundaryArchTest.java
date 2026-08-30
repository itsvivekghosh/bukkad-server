package com.bhukkad.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Phase 0 boundary freeze for the strangler-fig microservices migration.
 *
 * <p>Each business domain (payment, delivery, live, notification, …) may only
 * touch OTHER domains' aggregates through the owning domain's {@code api}
 * package ({@code com.bhukkad.order.api.OrderSummary},
 * {@code com.bhukkad.security.LiveSubscriptionAuthorizer}, …) — never through
 * the shared {@code com.bhukkad.entity} classes or raw repositories. This is
 * the seam along which the physical services get extracted in Phases 1–3.</p>
 *
 * <h2>Freeze, don't boil the ocean</h2>
 * <p>Payment/delivery still reach into the Order entity in ~16 pre-existing
 * places; unwinding them is tracked per-batch. Those legacy violations are
 * captured with {@link FreezingArchRule}: the violation store
 * ({@code src/test/resources/archunit_store}) records today's state and the
 * build fails only when NEW cross-domain coupling appears. Paying down a
 * frozen violation updates the store with
 * {@code mvn test -Darchunit.freeze.store.default.allowStoreUpdate=true}.</p>
 *
 * <p>Seams that are already clean (live → Order, notification → Order) are
 * enforced HARD — any regression fails immediately.</p>
 */
@Tag("architecture")
class DomainBoundaryArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.bhukkad");

    /**
     * Entities each domain OWNS — depending on these from the same domain is
     * fine. Everything else in {@code com.bhukkad.entity} is foreign.
     */
    private static final Map<String, Set<String>> DOMAIN_AGGREGATES = Map.of(
            "payment", Set.of("Payment", "Refund", "GiftCard", "GiftOrder", "Dispute"),
            "settlement", Set.of("SettlementRun", "RestaurantSettlement", "RiderEarning"),
            "wallet", Set.of("WalletTransaction"),
            "delivery", Set.of("DeliveryAgent", "RiderEarning", "RiderDeliveryBatch",
                    "RiderLocationUpdate", "OrderEtaSnapshot", "AgentShift", "AgentCodWallet",
                    "OrderDeliveryProof", "DeliverySurvey"),
            "notification", Set.of("NotificationPreference", "DeviceToken"),
            "live", Set.of(),
            "search", Set.of("TrendingDish"));

    private static final Set<String> MANAGED_DOMAIN_PACKAGES = Set.of(
            "com.bhukkad.payment", "com.bhukkad.wallet", "com.bhukkad.settlement",
            "com.bhukkad.delivery", "com.bhukkad.live",
            "com.bhukkad.notification", "com.bhukkad.search");

    private static Set<String> foreignEntitiesOf(String domain) {
        Set<String> own = DOMAIN_AGGREGATES.getOrDefault(domain, Set.of());
        return DOMAIN_AGGREGATES.values().stream()
                .flatMap(Set::stream)
                .filter(name -> !own.contains(name))
                .collect(Collectors.toSet());
    }

    /**
     * Cross-domain aggregate access is frozen per domain. New dependencies of a
     * managed domain on a FOREIGN aggregate entity fail the build.
     */
    @Test
    void managedDomains_mustNotGainNewForeignAggregateDependencies() {
        for (String domainPackage : MANAGED_DOMAIN_PACKAGES) {
            String domain = domainPackage.replace("com.bhukkad.", "");
            Set<String> foreign = foreignEntitiesOf(domain);

            ArchRule rule = noClasses()
                    .that().resideInAPackage(domainPackage + "..")
                    .should().dependOnClassesThat(new com.tngtech.archunit.base.DescribedPredicate<>(
                            "are foreign aggregate entities of the " + domain + " domain") {
                        @Override
                        public boolean test(com.tngtech.archunit.core.domain.JavaClass clazz) {
                            if (!clazz.getPackageName().equals("com.bhukkad.entity")) {
                                return false;
                            }
                            return foreign.contains(clazz.getSimpleName());
                        }
                    })
                    .because("cross-domain aggregate access must go through the owning " +
                            "domain's api package (strangler-fig Phase 0 seam)");

            // Legacy coupling is frozen per domain; pay it down batch by batch.
            // (The live -> OrderRepository dependency remains until the ETA service
            // moves behind a port; the hard rules below already pin entity.Order.)
            checkFrozen(rule);
        }
    }

    /**
     * The live module was unwound from the Order entity (it now uses
     * OrderOwnershipPort/OrderQueryPort). This seam is enforced HARD.
     */
    @Test
    void liveDomain_mustNotDependOnOrderAggregateOrItsRepository() {
        noClasses()
                .that().resideInAPackage("com.bhukkad.live..")
                .should().dependOnClassesThat(ORDER_AGGREGATE)
                .because("live consumes order data only through com.bhukkad.order.api and " +
                        "delivery ETA through com.bhukkad.delivery.api (Phase 0 seams; " +
                        "the Phase 3 order-service extraction swaps these for clients)")
                .check(CLASSES);
    }

    /**
     * Notification is the first physically extracted service (Phase 1). It
     * must not touch the Order aggregate at all — it reacts to events.
     */
    @Test
    void notificationDomain_mustNotDependOnOrderAggregate() {
        noClasses()
                .that().resideInAPackage("com.bhukkad.notification..")
                .should().dependOnClassesThat(ORDER_AGGREGATE)
                .because("notification consumes order events via Kafka/outbox; it never " +
                        "reads the order aggregate directly (Phase 1 extraction seam)")
                .check(CLASSES);
    }

    /**
     * The Order aggregate and its repository are order-domain internals. The
     * nested {@code OrderStatus} enum is deliberately allowed: it is the shared
     * status vocabulary carried by the Kafka event contracts.
     */
    private static final DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> ORDER_AGGREGATE =
            new DescribedPredicate<>("are the Order aggregate or its repository") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaClass clazz) {
                    String name = clazz.getName();
                    if (name.equals("com.bhukkad.entity.Order")
                            || name.equals("com.bhukkad.repository.OrderRepository")) {
                        return true;
                    }
                    return name.startsWith("com.bhukkad.entity.Order$")
                            && !name.endsWith("OrderStatus");
                }
            };

    /**
     * Security sits below every domain: role-routed identity resolution must
     * not depend on business domains except through the security-owned port
     * {@code LiveSubscriptionAuthorizer} that the live module implements.
     */
    @Test
    void securityDomain_mustNotDependOnBusinessDomainsExceptViaItsOwnPorts() {
        noClasses()
                .that().resideInAPackage("com.bhukkad.security..")
                .and().doNotHaveFullyQualifiedName("com.bhukkad.security.LiveSubscriptionAuthorizer")
                .should().dependOnClassesThat(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "reside in a managed business domain") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass clazz) {
                        String pkg = clazz.getPackageName();
                        if (pkg.equals("com.bhukkad.security") || pkg.startsWith("com.bhukkad.security.")) {
                            return false;
                        }
                        // order.api is the sanctioned seam into the order domain
                        if (pkg.startsWith("com.bhukkad.order.api")) {
                            return false;
                        }
                        return MANAGED_DOMAIN_PACKAGES.stream().anyMatch(pkg::startsWith)
                                || pkg.equals("com.bhukkad.order") || pkg.startsWith("com.bhukkad.order.");
                    }
                })
                .because("security is a lower-level concern; business access is " +
                        "inverted through security-owned ports (LiveSubscriptionAuthorizer)")
                .check(CLASSES);
    }

    /**
     * Outside the order domain, code that depends on order internals may only
     * see the {@code com.bhukkad.order.api} contracts. Frozen: payment/delivery
     * currently import OrderRepository directly and are migrated batch by batch.
     */
    @Test
    void orderInternals_areOnlyVisibleThroughOrderApiPackage() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(MANAGED_DOMAIN_PACKAGES.toArray(String[]::new))
                .should().dependOnClassesThat(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "are order-domain internal classes") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass clazz) {
                        String pkg = clazz.getPackageName();
                        if (!pkg.startsWith("com.bhukkad.order")) {
                            return false;
                        }
                        return !pkg.startsWith("com.bhukkad.order.api");
                    }
                })
                .because("the order service extraction (Phase 3) replaces these internals " +
                        "with an HTTP/gRPC client over the api contract");

        checkFrozen(rule);
    }

    private static void checkFrozen(ArchRule rule) {
        // Store path configured in src/test/resources/archunit.properties
        FreezingArchRule.freeze(rule).check(CLASSES);
    }
}
