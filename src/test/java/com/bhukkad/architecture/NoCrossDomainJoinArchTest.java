package com.bhukkad.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaModifier.PUBLIC;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the Phase 2 domain ownership boundary: repository {@code @Query}
 * methods must not reference entities from multiple owning domains. A
 * documented allowlist covers pre-existing cross-domain queries that are being
 * replaced by event-driven materialization (Phase 2) and service extraction
 * (Phase 3); any NEW cross-domain query fails the build.
 *
 * <p>Domain ownership is defined by entity class name and JOIN-path field name
 * (e.g. {@code JOIN o.customer} ⇒ CUSTOMER domain).</p>
 */
class NoCrossDomainJoinArchTest {

    private static final Pattern EXPLICIT_ENTITY = Pattern.compile(
            "(?i)(?:FROM|JOIN)\\s+([A-Z][A-Za-z0-9]*)");
    private static final Pattern JOIN_PATH_FIELD = Pattern.compile(
            "(?i)JOIN\\s+(?:FETCH\\s+)?\\w+\\.(\\w+)");

    // ── Entity class name → domain ───────────────────────────────────────────
    private static final Map<String, String> ENTITY_DOMAIN = Map.ofEntries(
            Map.entry("Order", "ORDER"), Map.entry("OrderItem", "ORDER"),
            Map.entry("OrderItemCustomization", "ORDER"), Map.entry("Cart", "ORDER"),
            Map.entry("CartItem", "ORDER"), Map.entry("CartItemCustomization", "ORDER"),
            Map.entry("GroupOrder", "ORDER"), Map.entry("GroupOrderMember", "ORDER"),
            Map.entry("GiftOrder", "ORDER"), Map.entry("GiftCard", "ORDER"),
            Map.entry("SubscriptionPlan", "ORDER"), Map.entry("SubscriptionDelivery", "ORDER"),
            Map.entry("OrderEtaSnapshot", "ORDER"), Map.entry("OrderInvoice", "ORDER"),
            Map.entry("OrderTimelineEvent", "ORDER"),
            Map.entry("Payment", "PAYMENT"), Map.entry("WalletTransaction", "PAYMENT"),
            Map.entry("Dispute", "PAYMENT"), Map.entry("IdempotencyRecord", "PAYMENT"),
            Map.entry("Restaurant", "RESTAURANT"), Map.entry("RestaurantOwner", "RESTAURANT"),
            Map.entry("MenuCategory", "RESTAURANT"), Map.entry("MenuItem", "RESTAURANT"),
            Map.entry("MenuVersion", "RESTAURANT"), Map.entry("MenuItemRating", "RESTAURANT"),
            Map.entry("CustomizationOption", "RESTAURANT"), Map.entry("CustomizationChoice", "RESTAURANT"),
            Map.entry("InventoryAlert", "RESTAURANT"), Map.entry("MenuItemImage", "RESTAURANT"),
            Map.entry("MenuItemAllergen", "RESTAURANT"), Map.entry("MenuItemIngredient", "RESTAURANT"),
            Map.entry("MenuItemTag", "RESTAURANT"), Map.entry("RestaurantFeature", "RESTAURANT"),
            Map.entry("RestaurantFoodType", "RESTAURANT"), Map.entry("RestaurantCuisine", "RESTAURANT"),
            Map.entry("RestaurantGallery", "RESTAURANT"),
            Map.entry("Customer", "CUSTOMER"), Map.entry("User", "CUSTOMER"),
            Map.entry("Address", "CUSTOMER"), Map.entry("DeviceToken", "CUSTOMER"),
            Map.entry("CustomerMembership", "CUSTOMER"), Map.entry("MembershipPlan", "CUSTOMER"),
            Map.entry("AffiliateCode", "CUSTOMER"), Map.entry("AffiliateReferral", "CUSTOMER"),
            Map.entry("UserReferralCode", "CUSTOMER"), Map.entry("FavoriteRestaurant", "CUSTOMER"),
            Map.entry("ConsentRecord", "CUSTOMER"), Map.entry("CustomerNotificationPreference", "CUSTOMER"),
            Map.entry("DeliveryAgent", "DELIVERY"), Map.entry("RiderDeliveryBatch", "DELIVERY"),
            Map.entry("RiderDeliveryBatchOrder", "DELIVERY"), Map.entry("RiderEarning", "DELIVERY"),
            Map.entry("RiderLocationUpdate", "DELIVERY"), Map.entry("DeliverySurvey", "DELIVERY"),
            Map.entry("OrderDeliveryProof", "DELIVERY"), Map.entry("AgentCodWallet", "DELIVERY"),
            Map.entry("AgentShift", "DELIVERY"), Map.entry("DeliveryZone", "DELIVERY"),
            Map.entry("ZoneSurgeRule", "DELIVERY"),
            Map.entry("Coupon", "PROMOTION"), Map.entry("CouponUsage", "PROMOTION"),
            Map.entry("PromoBanner", "PROMOTION"), Map.entry("PromotionCampaign", "PROMOTION"),
            Map.entry("CampaignUsage", "PROMOTION"), Map.entry("DynamicPricingRule", "PROMOTION"),
            Map.entry("SettlementRun", "SETTLEMENT"), Map.entry("RestaurantSettlement", "SETTLEMENT"),
            Map.entry("Review", "REVIEW"), Map.entry("ReviewImage", "REVIEW"),
            Map.entry("ChurnScore", "ADMIN"), Map.entry("FraudEvent", "ADMIN"),
            Map.entry("FraudReviewQueue", "ADMIN"), Map.entry("DataExportRequest", "ADMIN"),
            Map.entry("ExperimentExposure", "ADMIN"), Map.entry("SupportTicket", "ADMIN"),
            Map.entry("ApiKey", "ADMIN"), Map.entry("AuditEvent", "ADMIN"),
            Map.entry("City", "ADMIN"), Map.entry("CityConfig", "ADMIN"),
            Map.entry("Tenant", "ADMIN"), Map.entry("RestaurantOrderStat", "ADMIN"),
            Map.entry("RestaurantRatingSummary", "ADMIN"), Map.entry("TrendingDish", "ADMIN"));

    // ── JOIN path field name → domain (e.g. "o.customer", "r.deliveryAgent") ──
    private static final Map<String, String> PATH_FIELD_DOMAIN = Map.ofEntries(
            Map.entry("customer", "CUSTOMER"), Map.entry("address", "CUSTOMER"),
            Map.entry("deviceToken", "CUSTOMER"), Map.entry("membership", "CUSTOMER"),
            Map.entry("consentRecord", "CUSTOMER"),
            Map.entry("restaurant", "RESTAURANT"), Map.entry("owner", "RESTAURANT"),
            Map.entry("menuItem", "RESTAURANT"), Map.entry("category", "RESTAURANT"),
            Map.entry("menuVersion", "RESTAURANT"),
            Map.entry("deliveryAgent", "DELIVERY"), Map.entry("agent", "DELIVERY"),
            Map.entry("deliveryBatch", "DELIVERY"), Map.entry("riderLocationUpdate", "DELIVERY"),
            Map.entry("payment", "PAYMENT"), Map.entry("walletTransaction", "PAYMENT"),
            Map.entry("order", "ORDER"), Map.entry("cart", "ORDER"),
            Map.entry("subscription", "ORDER"), Map.entry("groupOrder", "ORDER"),
            Map.entry("review", "REVIEW"), Map.entry("coupon", "PROMOTION"),
            Map.entry("promotion", "PROMOTION"), Map.entry("campaign", "PROMOTION"),
            Map.entry("banner", "PROMOTION"), Map.entry("settlementRun", "SETTLEMENT"),
            Map.entry("ratingSummary", "ADMIN"), Map.entry("orderStat", "ADMIN"),
            Map.entry("trendingDish", "ADMIN"));

    // ── Allowlist: documented pre-existing cross-domain queries ─────────────
    // Deep-fetch detail queries join the full aggregate (order + customer +
    // restaurant + agent + payment) for a single read model; they are consumed
    // by the owning service and are replaced per-service in Phase 3. Aggregate
    // queries feed scheduled materialization and admin dashboards.
    private static final String REPO = "com.bhukkad.repository.";
    private static final Set<String> ALLOWED = Set.of(
            REPO + "OrderRepository.findByIdWithDetails",
            REPO + "OrderRepository.findByOrderNumberWithDetails",
            REPO + "OrderRepository.findByCustomerIdWithDetails",
            REPO + "OrderRepository.findByRestaurantIdWithDetails",
            REPO + "OrderRepository.findByRestaurantAndStatusWithDetails",
            REPO + "OrderRepository.findByDeliveryAgentIdWithDetails",
            REPO + "OrderRepository.findByDeliveryAgentIdAndStatusIn",
            REPO + "OrderRepository.findByDeliveryAgentIdAndStatus",
            REPO + "OrderRepository.findAvailableDeliveriesForAgent",
            REPO + "OrderRepository.findCustomerOrderSummaries",
            REPO + "OrderRepository.findRestaurantOrderSummaries",
            REPO + "OrderRepository.findDeliveryAgentOrderSummaries",
            REPO + "OrderRepository.findPendingSummariesForRestaurant",
            REPO + "OrderRepository.findKitchenActiveSummaries",
            REPO + "OrderRepository.findCustomerOrderSummariesCursor",
            REPO + "OrderRepository.findRestaurantOrderSummariesCursor",
            REPO + "OrderRepository.findDeliveryAgentOrderSummariesCursor",
            REPO + "OrderRepository.findCustomerOrderSummariesAfterCursor",
            REPO + "OrderRepository.findDeliveryAgentOrderSummariesAfterCursor",
            REPO + "OrderRepository.findRestaurantOrderSummariesAfterCursor",
            REPO + "OrderRepository.findCustomerScheduledOrderSummaries",
            REPO + "OrderRepository.findCustomerScheduledOrderSummariesAfterCursor",
            REPO + "OrderRepository.findTop10ByOrderByCreatedAtDesc",
            REPO + "OrderRepository.sumRestaurantRevenueSince",
            REPO + "OrderRepository.countRestaurantOrdersGroupedByStatus",
            REPO + "OrderRepository.findDailyDeliveredAggregates",
            REPO + "OrderRepository.findHourlyDeliveredCounts",
            REPO + "OrderRepository.findPlatformHourlyOrderCounts",
            REPO + "OrderRepository.countMeasurableDeliveriesSince",
            REPO + "OrderRepository.countOnTimeDeliveriesSince",
            REPO + "OrderRepository.findLateDeliveryTimestampsSince",
            REPO + "OrderItemRepository.findTrendingByCreatedSince",
            REPO + "OrderItemRepository.findTopSellingItems",
            REPO + "ReviewRepository.findByCustomerIdWithDetails",
            REPO + "ReviewRepository.findByRestaurantIdWithDetails",
            REPO + "ReviewRepository.findByOrderIdWithDetails",
            REPO + "ReviewRepository.findByIdWithDetails",
            REPO + "ReviewRepository.findByRestaurantIdAndModerationStatusWithDetails",
            REPO + "ReviewRepository.findByModerationStatusWithDetails",
            REPO + "ReviewRepository.getAverageDeliveryRatingByAgent",
            REPO + "ReviewRepository.countDeliveryRatingsByAgent",
            REPO + "RestaurantRepository.findByIdWithDetails",
            REPO + "RestaurantRepository.findAllByIdsWithDetails",
            REPO + "RestaurantRepository.findAllActiveWithDetails",
            REPO + "RestaurantRepository.findByOwnerIdWithDetails",
            REPO + "RestaurantRepository.searchByNameWithDetails",
            REPO + "RestaurantRepository.findByFilters",
            REPO + "CartRepository.findByCustomerIdWithRestaurant",
            REPO + "CartItemRepository.findByIdWithCart",
            REPO + "CartItemRepository.findByCartId",
            REPO + "CartItemRepository.findByCartIdWithMenuItem",
            REPO + "CouponRepository.findByCode",
            REPO + "CouponRepository.findByIdWithRestaurant",
            REPO + "CouponRepository.findActivePlatformCoupons",
            REPO + "CouponRepository.findActiveCouponsForRestaurant",
            REPO + "OrderDeliveryProofRepository.findByAgentAndStatus");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhukkad");
    }

    @Test
    void repositoryQueriesMustNotJoinAcrossDomains() {
        List<JavaClass> repositories = classes.stream()
                .filter(jc -> jc.getPackageName().contains(".repository"))
                .toList();

        Set<String> violations = new HashSet<>();
        for (JavaClass repo : repositories) {
            for (JavaMethod method : repo.getMethods()) {
                if (!method.getModifiers().contains(PUBLIC)) {
                    continue;
                }
                Optional<Query> queryAnn = method.tryGetAnnotationOfType(Query.class);
                if (queryAnn.isEmpty()) {
                    continue;
                }
                String queryText = queryAnn.map(Query::value).orElse("");
                if (queryText.isBlank()) {
                    continue;
                }

                Set<String> domains = domainsReferencedBy(queryText);
                String qualifiedName = repo.getName() + "." + method.getName();
                if (domains.size() > 1 && !ALLOWED.contains(qualifiedName)) {
                    violations.add(qualifiedName + " joins domains " + domains
                            + " | query: " + truncate(queryText, 100));
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Found " + violations.size() + " cross-domain repository @Query methods not in the allowlist:\n"
                        + String.join("\n", violations));
    }

    static Set<String> domainsReferencedBy(String jpql) {
        Set<String> domains = new HashSet<>();
        Matcher explicit = EXPLICIT_ENTITY.matcher(jpql);
        while (explicit.find()) {
            String domain = ENTITY_DOMAIN.get(explicit.group(1));
            if (domain != null) {
                domains.add(domain);
            }
        }
        Matcher path = JOIN_PATH_FIELD.matcher(jpql);
        while (path.find()) {
            String domain = PATH_FIELD_DOMAIN.get(path.group(1));
            if (domain != null) {
                domains.add(domain);
            }
        }
        return domains;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
