package com.bhukkad.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Restaurant/Menu/Inventory domain boundary rules for the first service
 * extraction (restaurant-service, approved architecture §8).
 *
 * <p>The restaurant domain ({@code com.bhukkad.restaurant},
 * {@code com.bhukkad.menu}, {@code com.bhukkad.inventory}, plus
 * {@code com.bhukkad.search} which stays folded in per the approved plan) owns
 * the restaurant/menu/catalog data model. It is the FIRST physically extracted
 * service, so its seams are guarded harder than the frozen
 * {@link DomainBoundaryArchTest} rules:</p>
 *
 * <ul>
 *   <li><b>HARD</b> — restaurant/menu/inventory must never depend on the order,
 *       payment, delivery, wallet, cart or customer domains (it does not today;
 *       any new dependency fails CI).</li>
 *   <li><b>HARD</b> — restaurant/menu/inventory must only use repositories it
 *       owns (RestaurantRepository, MenuItemRepository, MenuCategoryRepository,
 *       MenuVersionRepository). Accessing a foreign domain's repository would be
 *       a direct cross-service database access.</li>
 *   <li>One documented exception: {@code RestaurantDashboardService} aggregates
 *       settlement data for the owner dashboard through
 *       {@code RestaurantSettlementService}. This seam moves behind a port
 *       during extraction and must not grow.</li>
 * </ul>
 */
@Tag("architecture")
class RestaurantBoundaryArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.bhukkad");

    private static final String[] RESTAURANT_PACKAGES = {
            "com.bhukkad.restaurant..",
            "com.bhukkad.menu..",
            "com.bhukkad.inventory.."
    };

    @Test
    void restaurantDomain_mustNotDependOnOtherServiceDomains() {
        noClasses()
                .that().resideInAnyPackage(RESTAURANT_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.order..",
                        "com.bhukkad.payment..",
                        "com.bhukkad.delivery..",
                        "com.bhukkad.wallet..",
                        "com.bhukkad.cart..",
                        "com.bhukkad.customer..")
                .because("restaurant-service is the first extraction; it must stay "
                        + "independent of order/payment/delivery/wallet/cart/customer internals")
                .check(CLASSES);
    }

    @Test
    void restaurantDomain_mustOnlyUseOwnedRepositories() {
        // Repositories the restaurant/menu/inventory domain owns. Depending on
        // any other repository is a direct cross-service database access.
        var owned = java.util.Set.of(
                "com.bhukkad.repository.RestaurantRepository",
                "com.bhukkad.repository.MenuItemRepository",
                "com.bhukkad.repository.MenuCategoryRepository",
                "com.bhukkad.repository.MenuVersionRepository",
                "com.bhukkad.repository.MenuItemRatingRepository",
                "com.bhukkad.repository.CuisineRepository",
                "com.bhukkad.repository.RestaurantOwnerRepository",
                "com.bhukkad.repository.InventoryAlertRepository",
                "com.bhukkad.repository.DynamicPricingRuleRepository");
        noClasses()
                .that().resideInAnyPackage(RESTAURANT_PACKAGES)
                .should().dependOnClassesThat(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "are repositories not owned by the restaurant domain") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass clazz) {
                        return clazz.getPackageName().startsWith("com.bhukkad.repository.")
                                && !owned.contains(clazz.getName());
                    }
                })
                .because("restaurant must not access other services' data through "
                        + "their repositories; cross-service data crosses the seam "
                        + "via the owning service's API/events")
                .check(CLASSES);
    }

    @Test
    void restaurantDashboard_mustNotGrowNewSettlementCoupling() {
        // The owner dashboard aggregates settlement data. This is the single
        // documented seam into the settlement domain; it moves behind a port
        // when restaurant-service is extracted, so no other restaurant/menu/
        // inventory class may add settlement dependencies.
        noClasses()
                .that().resideInAnyPackage(RESTAURANT_PACKAGES)
                .and().doNotHaveFullyQualifiedName("com.bhukkad.restaurant.RestaurantDashboardService")
                .should().dependOnClassesThat().resideInAPackage("com.bhukkad.settlement..")
                .because("settlement data is payment-service-owned; only the "
                        + "dashboard aggregation seam may reach it until it moves behind a port")
                .check(CLASSES);
    }

    @Test
    void searchAutocomplete_staysWithinRestaurantDomainSeam() {
        // Search stays folded into restaurant-service (approved plan §6). It may
        // depend on feature flags (platform infra) but must not reach into other
        // business domains.
        noClasses()
                .that().resideInAPackage("com.bhukkad.search..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.order..",
                        "com.bhukkad.payment..",
                        "com.bhukkad.delivery..",
                        "com.bhukkad.wallet..",
                        "com.bhukkad.cart..",
                        "com.bhukkad.customer..",
                        "com.bhukkad.settlement..")
                .because("search is part of restaurant-service; it must not couple "
                        + "to other services' internals either")
                .check(CLASSES);
    }
}
