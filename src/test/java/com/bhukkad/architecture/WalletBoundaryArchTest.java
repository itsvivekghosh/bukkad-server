package com.bhukkad.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Wallet domain boundary rules for the wallet-first service extraction.
 *
 * <p>The wallet domain ({@code com.bhukkad.wallet}) owns {@code wallet_balances}
 * (V64) and {@code wallet_transactions}. It is the FIRST candidate for physical
 * extraction, so its seams are guarded harder than the frozen
 * {@link DomainBoundaryArchTest} rules:</p>
 *
 * <ul>
 *   <li><b>HARD</b> — wallet must never touch the order, delivery, restaurant
 *       or menu domains (it does not today; any new dependency fails CI).</li>
 *   <li><b>HARD</b> — wallet must never reference the Order aggregate or its
 *       repository.</li>
 *   <li>Customer/payment access is currently legitimate (wallet top-up creates
 *       a payment; wallet history verifies the customer exists) and is tracked
 *       separately for pay-down behind ports during extraction.</li>
 * </ul>
 */
@Tag("architecture")
class WalletBoundaryArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.bhukkad");

    @Test
    void walletDomain_mustNotDependOnOrderDeliveryRestaurantOrMenuDomains() {
        noClasses()
                .that().resideInAPackage("com.bhukkad.wallet..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bhukkad.delivery..",
                        "com.bhukkad.restaurant..",
                        "com.bhukkad.menu..")
                .because("wallet is the first service to extract; it must stay "
                        + "independent of delivery/restaurant/menu internals")
                .check(CLASSES);
    }

    @Test
    void walletDomain_mustNotDependOnOrderRepository() {
        // Wallet may access the Order entity transitively through the Payment
        // entity (Payment.getOrder() is a payment-domain relationship), but it
        // must never import OrderRepository directly — that would be a direct
        // cross-domain database access that bypasses the extraction seam.
        noClasses()
                .that().resideInAPackage("com.bhukkad.wallet..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.bhukkad.repository.OrderRepository")
                .because("wallet must not access orders through the repository; "
                        + "order data cross the seam only via the order API ports")
                .check(CLASSES);
    }

    @Test
    void otherDomains_mustNotDependOnWalletInternals() {
        // Only the wallet domain may use its internal classes (WalletBalance,
        // WalletBalanceRepository). Other domains access wallet state through
        // the sanctioned WalletService, WalletTopUpService (controller entry)
        // or WalletQueryService (read-only query API).
        noClasses()
                .that().resideOutsideOfPackage("com.bhukkad.wallet..")
                .should().dependOnClassesThat().haveSimpleName("WalletBalance")
                .orShould().dependOnClassesThat().haveSimpleName("WalletBalanceRepository")
                .because("wallet internals (WalletBalance, WalletBalanceRepository) "
                        + "are wallet-owned and must be reached only through "
                        + "WalletService/ WalletQueryService")
                .check(CLASSES);
    }
}
