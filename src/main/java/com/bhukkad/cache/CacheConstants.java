package com.bhukkad.cache;

/**
 * Central registry of cache names and key-building tokens.
 *
 * <p>Cache names double as the first segment of every Redis key produced by
 * {@link CacheKeyGenerator}, and they match the named cache configurations
 * declared in {@code RedisConfig}. Keeping them here prevents drift between
 * the TTL configuration in {@code application.yml} ({@code cache.ttl.<name>})
 * and the keys actually written at runtime.
 */
public final class CacheConstants {

    // Cache Names
    public static final String RESTAURANT = "restaurant";
    public static final String RESTAURANT_LIST = "restaurant-list";
    public static final String RESTAURANT_SEARCH = "restaurant-search";
    public static final String RESTAURANT_FILTER = "restaurant-filter";
    public static final String MENU_ITEM = "menu-item";
    public static final String MENU_ITEM_LIST = "menu-item-list";
    public static final String MENU_CATEGORY = "menu-category";
    public static final String MENU_CATEGORY_LIST = "menu-category-list";
    public static final String CUISINE = "cuisine";
    public static final String CUISINE_LIST = "cuisine-list";
    public static final String USER_PROFILE = "user-profile";
    public static final String CART = "cart";
    public static final String ORDER = "order";
    public static final String ORDER_TRACK = "order-track";
    public static final String ORDER_LIST = "order-list";
    public static final String KITCHEN_QUEUE = "kitchen-queue";
    public static final String REVIEW = "review";
    public static final String REVIEW_LIST = "review-list";
    public static final String COUPON = "coupon";
    public static final String COUPON_LIST = "coupon-list";
    public static final String BESTSELLER = "bestseller";
    public static final String RECOMMENDED = "recommended";
    public static final String MENU_SEARCH = "menu-search";
    public static final String RESTAURANT_NEARBY = "restaurant-nearby";
    public static final String ADMIN = "admin";
    /** Composed customer home feed (banners, campaigns, membership plans). */
    public static final String HOME_FEED = "home-feed";
    /** Per-request delivery serviceability verdict for a restaurant/location pair. */
    public static final String SERVICEABILITY = "serviceability";

    // Key Prefixes
    public static final String KEY_PREFIX = "bhukkad:";
    public static final String KEY_SEPARATOR = ":";

    private CacheConstants() {}
}