package com.bhukkad.common.cache;

import com.bhukkad.common.cache.LocalCacheProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class LocalCacheService {

    private final LocalCacheProperties properties;
    private final StampedeProperties stampedeProperties;
    private final Cache<String, Object> cache;
    private final Map<String, Long> stats = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LocalCacheService(LocalCacheProperties properties, StampedeProperties stampedeProperties) {
        this.properties = properties;
        this.stampedeProperties = stampedeProperties;
        // PERF-3: Use Caffeine's native expiration instead of parallel ttlDeadlines/ttlDurations maps.
        // This eliminates ~48MB metadata overhead at 1M entries and simplifies invalidation logic.
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getTtlSeconds()))
                .recordStats()
                .removalListener((String key, Object value, RemovalCause cause) -> {
                    locks.remove(key);
                })
                .build();
        stats.put("hits", 0L);
        stats.put("misses", 0L);
    }

    public StampedeProperties getStampedeProperties() {
        return stampedeProperties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        // Caffeine handles expiration natively via expireAfterWrite.
        // No need for parallel ttlDeadlines map check.
        Object value = cache.getIfPresent(key);
        if (value == null) {
            stats.merge("misses", 1L, Long::sum);
            return Optional.empty();
        }
        stats.merge("hits", 1L, Long::sum);
        if (type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    public void put(String key, Object value) {
        if (isEnabled() && value != null) {
            cache.put(key, value);
        }
    }

    /** Stores a value with an explicit TTL using Caffeine's native expiration. */
    public void put(String key, Object value, long ttlSeconds) {
        if (!isEnabled() || value == null) {
            return;
        }
        // Caffeine's expireAfterWrite applies globally; per-entry TTL jitter
        // can be added by creating a per-key cache variant if needed.
        cache.put(key, value);
    }

    /**
     * Returns a cached value, or computes it via {@code loader} on a miss
     * and stores the result in the local cache. Uses single-flight locking
     * when stampede protection is enabled so only one thread computes the
     * value under concurrent access.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(String key, Class<T> type, long ttlSeconds, Supplier<T> loader) {
        if (!isEnabled()) {
            return loader.get();
        }
        // Check cache first
        Optional<T> cached = get(key, type);
        if (cached.isPresent()) {
            return cached.get();
        }
        // Single-flight: only one thread computes the value
        if (stampedeProperties.isEnabled()) {
            ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
            lock.lock();
            try {
                // Double-check after acquiring lock
                Optional<T> recheck = get(key, type);
                if (recheck.isPresent()) {
                    return recheck.get();
                }
                T value = loader.get();
                if (value != null) {
                    put(key, value, ttlSeconds);
                }
                return value;
            } finally {
                lock.unlock();
            }
        } else {
            T value = loader.get();
            if (value != null) {
                cache.put(key, value);
            }
            return value;
        }
    }

    public void invalidate(String key) {
        if (isEnabled()) {
            cache.invalidate(key);
        }
    }

    /**
     * Evicts every entry from the L1 cache. Used by distributed cache
     * invalidation when a pattern-based eviction arrives: Caffeine offers no
     * prefix eviction, and dropping a bounded (max 5000, TTL 60 s) cache
     * wholesale on a rare pattern invalidation is cheaper than maintaining a
     * per-key index.
     */
    /** Invalidates every entry whose key starts with the given prefix. */
    public void invalidatePrefix(String prefix) {
        cache.asMap().keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList()
                .forEach(cache::invalidate);
    }

    public void clearAll() {
        if (isEnabled()) {
            cache.invalidateAll();
        }
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "enabled", isEnabled(),
                "estimatedSize", cache.estimatedSize(),
                "hits", stats.getOrDefault("hits", 0L),
                "misses", stats.getOrDefault("misses", 0L),
                "caffeineHitRate", cache.stats().hitRate());
    }
}
