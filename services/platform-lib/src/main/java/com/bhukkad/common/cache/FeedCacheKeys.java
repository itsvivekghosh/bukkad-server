package com.bhukkad.common.cache;

/**
 * Canonical key builders and TTL constants for geospatial feed caching.
 *
 * <p>Keys are namespaced under {@link CacheConstants#KEY_PREFIX} by
 * {@link com.bhukkad.common.cache.RedisCacheService}, so callers pass the
 * logical name only (e.g. {@code "feed:nearby:" + geohash}).</p>
 */
public final class FeedCacheKeys {

    // Logical cache names (first segment of Redis key)
    public static final String FEED_GEO = "feed:geo";
    public static final String FEED_NEARBY = "feed:nearby";
    public static final String FEED_POST_DETAIL = "feed:post";
    public static final String FEED_L1 = "feed:l1";
    public static final String FEED_L2 = "feed:l2";

    // TTLs in seconds
    /** L1 in-JVM cache: very short to bound staleness under high write volume. */
    public static final long FEED_NEARBY_L1_TTL_SECONDS = 30;
    /** L2 Redis cache: longer but still bounded for feed freshness. */
    public static final long FEED_NEARBY_L2_TTL_SECONDS = 600; // 10 minutes
    /** Post detail cache: matches the segment rebuild interval (30s). */
    public static final long FEED_POST_TTL_SECONDS = 60;

    private FeedCacheKeys() {
    }

    /**
     * Build a deterministic cache key for a nearby-geohash query.
     *
     * @param geohash   4-char geohash prefix (~20 km cells)
     * @param radiusBucket bucketed radius string (e.g. "5", "10", "20")
     * @param cursor    pagination cursor, or blank for first page
     * @return cache key logical name
     */
    public static String nearbyKey(String geohash, String radiusBucket, String cursor) {
        String cursorPart = (cursor == null || cursor.isBlank()) ? "-" : cursor;
        return FEED_NEARBY + ":" + geohash + ":" + radiusBucket + ":" + cursorPart;
    }

    /**
     * Logarithmic radius bucketing to limit cache key cardinality.
     */
    public static String radiusBucket(double radiusKm) {
        if (radiusKm <= 1) return "1";
        if (radiusKm <= 2) return "2";
        if (radiusKm <= 5) return "5";
        if (radiusKm <= 10) return "10";
        if (radiusKm <= 20) return "20";
        if (radiusKm <= 50) return "50";
        return "100";
    }
}
