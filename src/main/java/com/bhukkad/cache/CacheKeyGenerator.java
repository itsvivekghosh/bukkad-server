package com.bhukkad.cache;

import com.bhukkad.entity.MenuItem;
import java.util.Set;
import java.util.stream.Collectors;

public final class CacheKeyGenerator {

    private CacheKeyGenerator() {}

    // Restaurant keys
    public static String restaurant(Long id) {
        return CacheConstants.RESTAURANT + CacheConstants.KEY_SEPARATOR + id;
    }

    public static String restaurantList() {
        return CacheConstants.RESTAURANT_LIST + CacheConstants.KEY_SEPARATOR + "all";
    }

    public static String restaurantList(Long tenantId) {
        if (tenantId == null) return restaurantList();
        return CacheConstants.RESTAURANT_LIST + CacheConstants.KEY_SEPARATOR + "tenant:" + tenantId;
    }

    public static String restaurantsByOwner(Long ownerId) {
        return CacheConstants.RESTAURANT_LIST + CacheConstants.KEY_SEPARATOR + "owner:" + ownerId;
    }

    public static String restaurantSearch(String keyword) {
        if (keyword == null) return CacheConstants.RESTAURANT_SEARCH + CacheConstants.KEY_SEPARATOR + "all";
        String slug = keyword.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        if (slug.length() > 50) slug = org.springframework.util.DigestUtils.md5DigestAsHex(slug.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (slug.isEmpty()) slug = "all";
        return CacheConstants.RESTAURANT_SEARCH + CacheConstants.KEY_SEPARATOR + slug;
    }

    public static String restaurantFilter(Long cuisineId, Boolean isPureVeg) {
        return CacheConstants.RESTAURANT_FILTER + CacheConstants.KEY_SEPARATOR + cuisineId + ":" + isPureVeg;
    }

    // Menu keys
    public static String menuItem(Long id) {
        return CacheConstants.MENU_ITEM + CacheConstants.KEY_SEPARATOR + id;
    }

    public static String menuItemsByRestaurant(Long restaurantId) {
        return CacheConstants.MENU_ITEM_LIST + CacheConstants.KEY_SEPARATOR + "restaurant:" + restaurantId;
    }

    public static String menuItemsByCategory(Long categoryId) {
        return CacheConstants.MENU_ITEM_LIST + CacheConstants.KEY_SEPARATOR + "category:" + categoryId;
    }

    public static String menuItemsByIds(java.util.List<Long> ids) {
        return CacheConstants.MENU_ITEM_LIST + CacheConstants.KEY_SEPARATOR + "ids:"
                + ids.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    public static String restaurantsByIds(java.util.List<Long> ids) {
        return CacheConstants.RESTAURANT_LIST + CacheConstants.KEY_SEPARATOR + "ids:"
                + ids.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    public static String bestsellers(Long restaurantId) {
        return CacheConstants.BESTSELLER + CacheConstants.KEY_SEPARATOR + restaurantId;
    }

    public static String recommended(Long restaurantId) {
        return CacheConstants.RECOMMENDED + CacheConstants.KEY_SEPARATOR + restaurantId;
    }

    public static String menuCategory(Long id) {
        return CacheConstants.MENU_CATEGORY + CacheConstants.KEY_SEPARATOR + id;
    }

    public static String menuCategoriesByRestaurant(Long restaurantId) {
        return CacheConstants.MENU_CATEGORY_LIST + CacheConstants.KEY_SEPARATOR + "restaurant:" + restaurantId;
    }

    // Cuisine keys
    public static String cuisine(Long id) {
        return CacheConstants.CUISINE + CacheConstants.KEY_SEPARATOR + id;
    }

    public static String cuisineList() {
        return CacheConstants.CUISINE_LIST + CacheConstants.KEY_SEPARATOR + "all";
    }

    // User keys
    public static String userProfile(Long userId) {
        return CacheConstants.USER_PROFILE + CacheConstants.KEY_SEPARATOR + userId;
    }

    // Cart keys
    public static String cart(Long customerId) {
        return CacheConstants.CART + CacheConstants.KEY_SEPARATOR + customerId;
    }

    // Order keys
    public static String order(Long orderId) {
        return CacheConstants.ORDER + CacheConstants.KEY_SEPARATOR + orderId;
    }

    /**
     * Key for a live order-tracking snapshot.
     * <p>
     * The key is scoped by <em>both</em> order and customer. Scoping by customer is a
     * security requirement, not an optimisation: the tracking payload is only ever
     * served to the order's owner, so a key shared across users would let a cached
     * entry written for the owner be returned to a different caller on a subsequent
     * request (an IDOR through the cache) even though the service performs an
     * ownership check on the database path.
     *
     * @param orderId    the order being tracked
     * @param customerId the authenticated customer the snapshot was rendered for
     */
    public static String orderTrack(Long orderId, Long customerId) {
        return CacheConstants.ORDER_TRACK + CacheConstants.KEY_SEPARATOR
                + orderId + ":customer:" + customerId;
    }

    public static String kitchenQueue(Long restaurantId) {
        return CacheConstants.KITCHEN_QUEUE + CacheConstants.KEY_SEPARATOR + restaurantId;
    }

    public static String customerOrders(Long customerId) {
        return CacheConstants.ORDER_LIST + CacheConstants.KEY_SEPARATOR + "customer:" + customerId;
    }

    public static String restaurantOrders(Long restaurantId) {
        return CacheConstants.ORDER_LIST + CacheConstants.KEY_SEPARATOR + "restaurant:" + restaurantId;
    }

    // Review keys
    public static String restaurantReviews(Long restaurantId) {
        return CacheConstants.REVIEW_LIST + CacheConstants.KEY_SEPARATOR + "restaurant:" + restaurantId;
    }

    // Coupon keys
    public static String coupon(String code) {
        return CacheConstants.COUPON + CacheConstants.KEY_SEPARATOR + code;
    }

    public static String activeCoupons(Long restaurantId) {
        return CacheConstants.COUPON_LIST + CacheConstants.KEY_SEPARATOR + "restaurant:" + restaurantId;
    }

    public static String menuSearch(String keyword) {
        if (keyword == null) return CacheConstants.MENU_SEARCH + CacheConstants.KEY_SEPARATOR + "all";
        String slug = keyword.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        if (slug.length() > 50) slug = org.springframework.util.DigestUtils.md5DigestAsHex(slug.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (slug.isEmpty()) slug = "all";
        return CacheConstants.MENU_SEARCH + CacheConstants.KEY_SEPARATOR + slug;
    }

    public static String menuItemsByDiet(Long restaurantId, MenuItem.FoodType foodType, Set<String> excludeAllergens, MenuItem.SpiceLevel maxSpiceLevel) {
        String allergensPart = (excludeAllergens == null || excludeAllergens.isEmpty()) ? "none" : excludeAllergens.stream().sorted().collect(Collectors.joining(","));
        String foodTypePart = foodType != null ? foodType.name() : "any";
        String spicePart = maxSpiceLevel != null ? maxSpiceLevel.name() : "any";
        return CacheConstants.MENU_ITEM_LIST + CacheConstants.KEY_SEPARATOR + "diet:" + restaurantId + ":" + foodTypePart + ":" + allergensPart + ":" + spicePart;
    }

    public static String restaurantNearby(double latitude, double longitude, double radiusKm) {
        // Quantize to 3 decimal places (~100m grid) to avoid key fragmentation from floating point noise
        String lat = String.format(java.util.Locale.ROOT, "%.3f", latitude);
        String lon = String.format(java.util.Locale.ROOT, "%.3f", longitude);
        String radius = String.format(java.util.Locale.ROOT, "%.2f", radiusKm);
        return CacheConstants.RESTAURANT_NEARBY + CacheConstants.KEY_SEPARATOR
                + lat + ":" + lon + ":" + radius;
    }

    public static String adminDashboard() {
        return CacheConstants.ADMIN + CacheConstants.KEY_SEPARATOR + "dashboard";
    }

    public static String adminRevenue(int days) {
        return CacheConstants.ADMIN + CacheConstants.KEY_SEPARATOR + "revenue:" + days;
    }

    public static String adminAnalytics() {
        return CacheConstants.ADMIN + CacheConstants.KEY_SEPARATOR + "analytics";
    }

    // Home feed keys

    /**
     * Key for the active promo banner carousel shown on the customer home feed.
     * Global (not user-scoped) because banner eligibility is not personalised.
     */
    public static String homeFeedBanners() {
        return CacheConstants.HOME_FEED + CacheConstants.KEY_SEPARATOR + "banners";
    }

    /**
     * Key for the active promotion campaign list shown on the customer home feed.
     */
    public static String homeFeedCampaigns() {
        return CacheConstants.HOME_FEED + CacheConstants.KEY_SEPARATOR + "campaigns";
    }

    /**
     * Key for the active membership plan catalogue shown on the customer home feed.
     */
    public static String homeFeedMembershipPlans() {
        return CacheConstants.HOME_FEED + CacheConstants.KEY_SEPARATOR + "membership-plans";
    }

    // Serviceability keys

    /**
     * Key for a delivery serviceability verdict. Every input that can change the
     * verdict is part of the key: the restaurant, the drop coordinates and the
     * cart subtotal (which drives free-delivery thresholds and fee slabs).
     * Coordinates are concatenated raw, mirroring {@link #restaurantNearby(double, double, double)}.
     */
    public static String serviceability(Long restaurantId, double latitude, double longitude, double subtotal) {
        String lat = String.format(java.util.Locale.ROOT, "%.3f", latitude);
        String lon = String.format(java.util.Locale.ROOT, "%.3f", longitude);
        String sub = String.format(java.util.Locale.ROOT, "%.2f", subtotal);
        return CacheConstants.SERVICEABILITY + CacheConstants.KEY_SEPARATOR
                + "restaurant:" + restaurantId + ":" + lat + ":" + lon + ":" + sub;
    }

    // Invalidation patterns
    public static String restaurantPattern() {
        return CacheConstants.RESTAURANT;
    }

    public static String menuItemPattern(Long restaurantId) {
        return CacheConstants.MENU_ITEM + CacheConstants.KEY_SEPARATOR + "*restaurant:" + restaurantId;
    }

    public static String orderPattern(Long customerId) {
        return CacheConstants.ORDER_LIST + CacheConstants.KEY_SEPARATOR + "customer:" + customerId;
    }

    /**
     * Invalidation pattern matching every customer-scoped tracking snapshot of one
     * order. Required because {@link #orderTrack(Long, Long)} embeds the customer id,
     * so an exact-key delete would no longer clear the owner's entry on status change.
     *
     * @param orderId the order whose tracking snapshots should be evicted
     */
    public static String orderTrackPattern(Long orderId) {
        return CacheConstants.ORDER_TRACK + CacheConstants.KEY_SEPARATOR + orderId + ":";
    }
}