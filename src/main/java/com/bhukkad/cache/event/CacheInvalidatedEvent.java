package com.bhukkad.cache.event;

/**
 * Published when a cache entry or pattern is invalidated locally.
 *
 * <p>Listeners may use this to perform side-effects (for example publishing
 * a Redis message for distributed invalidation).</p>
 */
public class CacheInvalidatedEvent {

    private final String cacheName;
    private final String key;
    private final boolean pattern;

    public CacheInvalidatedEvent(String cacheName, String key, boolean pattern) {
        this.cacheName = cacheName;
        this.key = key;
        this.pattern = pattern;
    }

    public String getCacheName() {
        return cacheName;
    }

    public String getKey() {
        return key;
    }

    public boolean isPattern() {
        return pattern;
    }
}
