package com.bhukkad.cache;

public final class CacheKeyGenerator {

    private CacheKeyGenerator() {}

    // Restaurant keys
    public static String restaurant(Long id) {
        return CacheConstants.RESTAURANT + CacheConstants.KEY_SEPARATOR + id;
    }

    public static String restaurantList() {
        return CacheConstants.RESTAURANT_LIST + CacheConstants.KEY_SEPARATOR + "all";
    }

    public static String restaurantsByOwner(Long ownerId) {
        return CacheConstants.RESTAURANT_LIST + CacheConstants.KEY_SEPARATOR + "owner:" + ownerId;
    }

    public static String restaurantSearch(String keyword) {
        return CacheConstants.RESTAURANT_SEARCH + CacheConstants.KEY_SEPARATOR + keyword.toLowerCase().trim();
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
}