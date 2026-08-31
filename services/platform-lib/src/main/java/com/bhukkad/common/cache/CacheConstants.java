package com.bhukkad.common.cache;

/**
 * Canonical cache key constants shared by all services (plan §2.3 cache layer).
 */
public final class CacheConstants {

    public static final String KEY_PREFIX = "bhukkad:";

    public static final String RESTAURANT_MENU = "restaurant:menu";
    public static final String ORDER = "order";
    public static final String WALLET_BALANCE = "wallet:balance";
    public static final String SERVICEABILITY = "serviceability";
    public static final String HOME_FEED = "home:feed";

    private CacheConstants() {
    }
}
