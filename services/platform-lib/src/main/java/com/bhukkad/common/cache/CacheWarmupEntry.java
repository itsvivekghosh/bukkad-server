package com.bhukkad.common.cache;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A single warmup contribution: a cache key, its TTL, and the loader that
 * produces the value to store in L1+L2.
 */
public record CacheWarmupEntry(String key, long ttlSeconds, Supplier<Object> loader) {
    public CacheWarmupEntry {
        Objects.requireNonNull(key, "Cache warmup key must not be null");
        Objects.requireNonNull(loader, "Cache warmup loader must not be null");
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("Cache warmup TTL must be positive");
        }
    }
}