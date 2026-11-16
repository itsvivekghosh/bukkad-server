package com.bhukkad.common.cache;

import java.util.List;

/**
 * Pluggable registry of hot keys to warm into the L1+L2 cache on startup.
 *
 * <p>Each service that wants to contribute warmup keys implements this
 * interface and registers a Spring bean. {@link CacheWarmupService} collects
 * every {@code CacheWarmupRegistry} bean, so platform-lib never needs to know
 * about any individual service's keys.</p>
 */
public interface CacheWarmupRegistry {

    /**
     * Keys this service wants preloaded into the cache at startup, together
     * with the TTL and loader that produces each value.
     *
     * @return never {@code null}; may be empty.
     */
    List<CacheWarmupEntry> warmupEntries();
}