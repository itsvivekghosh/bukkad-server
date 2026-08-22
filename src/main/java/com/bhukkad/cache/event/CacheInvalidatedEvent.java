package com.bhukkad.cache.event;

/**
 * Published when a cache entry or pattern is invalidated locally.
 *
 * <p>Listeners may use this to perform side-effects (for example publishing
 * a Redis message for distributed invalidation).</p>
 *
 * <p>The class is serialized to and deserialized from Redis messages by
 * Jackson, so it exposes a no-arg constructor plus getters/setters in
 * addition to the convenience all-args constructor.</p>
 */
public class CacheInvalidatedEvent {

    private String cacheName;
    private String key;
    private boolean pattern;

    /** No-arg constructor required for Jackson deserialization of Redis messages. */
    public CacheInvalidatedEvent() {
    }

    public CacheInvalidatedEvent(String cacheName, String key, boolean pattern) {
        this.cacheName = cacheName;
        this.key = key;
        this.pattern = pattern;
    }

    public String getCacheName() {
        return cacheName;
    }

    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public boolean isPattern() {
        return pattern;
    }

    public void setPattern(boolean pattern) {
        this.pattern = pattern;
    }
}
