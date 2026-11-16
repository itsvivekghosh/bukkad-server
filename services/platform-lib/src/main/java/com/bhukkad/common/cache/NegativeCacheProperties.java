package com.bhukkad.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@code @NegativeCacheable} negative caching.
 *
 * <p>Negative caching stores a negative marker in L2 (Redis) for a short TTL
 * so repeated lookups for a genuinely missing entity short-circuit against
 * the cache instead of re-hitting the database. The default TTL of 5s is
 * intentionally short: confirming a miss is cheap, and the cost of serving a
 * stale negative for 5s is far lower than a DB hit for every cold-miss burst.</p>
 */
@ConfigurationProperties(prefix = "bhukkad.cache.negative")
public class NegativeCacheProperties {

    private boolean enabled = true;

    /** Short TTL (seconds) for negative (empty) entries. */
    private long ttlSeconds = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}