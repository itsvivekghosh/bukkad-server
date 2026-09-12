package com.bhukkad.restaurant.infrastructure.cache;

/**
 * Canonical cache keys for the restaurant read paths (PERF-3). The L2 key is
 * {@code bhukkad:} + the value returned here; invalidation fans these names
 * out through {@link MenuCacheInvalidator}.
 */
public final class RestaurantCacheKeys {

    /** Composite home-feed projection (active restaurants + banners). */
    public static final String FEED = "feed:v1";
    /** Per-restaurant menu snapshot: {@code menu:restaurant:<id>}. */
    public static final String MENU_SNAPSHOT_PREFIX = "menu:restaurant:";
    /** Rendered menu item for the batch surface: {@code menu:item:<id>}. */
    public static final String MENU_ITEM_PREFIX = "menu:item:";

    /** Feed projection lifetime (guide §6 PERF-3: 30–60 s window). */
    public static final long FEED_TTL_SECONDS = 45L;
    /** Menu snapshot lifetime (guide §6 PERF-3: 60–300 s window). */
    public static final long MENU_SNAPSHOT_TTL_SECONDS = 300L;
    /** Rendered menu item lifetime (guide §6 PERF-3: per-id 60 s). */
    public static final long MENU_ITEM_TTL_SECONDS = 60L;

    private RestaurantCacheKeys() {
    }

    public static String menuSnapshot(Long restaurantId) {
        return MENU_SNAPSHOT_PREFIX + restaurantId;
    }

    public static String menuItem(Long menuItemId) {
        return MENU_ITEM_PREFIX + menuItemId;
    }
}
