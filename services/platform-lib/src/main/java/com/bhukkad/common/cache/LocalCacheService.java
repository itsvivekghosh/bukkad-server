package com.bhukkad.common.cache;

import com.bhukkad.common.cache.LocalCacheProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
    private final Map<String, Long> ttlDeadlines = new ConcurrentHashMap<>();
    private final Map<String, Long> ttlDurations = new ConcurrentHashMap<>();

    public LocalCacheService(LocalCacheProperties properties, StampedeProperties stampedeProperties) {
        this.properties = properties;
        this.stampedeProperties = stampedeProperties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getTtlSeconds()))
                .recordStats()
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
        // Probabilistic early expiration: as an entry nears its TTL deadline we
        // may treat it as expired with rising probability, spreading
        // recomputation across the window instead of a synchronized stampede at
        // the exact expiry moment. Safe to recompute: the loader refreshes the
        // value with a fresh TTL.
        Long deadline = ttlDeadlines.get(key);
        if (deadline != null) {
            long now = System.currentTimeMillis();
            if (now > deadline || probabilisticallyEarlyExpired(key, deadline, now)) {
                invalidate(key);
                stats.merge("misses", 1L, Long::sum);
                return Optional.empty();
            }
        }
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

    /**
     * Returns {@code true} when the entry is inside the probabilistic early
     * expiration window and the random draw says "refresh now".
     */
    private boolean probabilisticallyEarlyExpired(String key, long deadline, long now) {
        if (!stampedeProperties.isEnabled() || stampedeProperties.getEarlyExpirePercent() <= 0) {
            return false;
        }
        Long ttlMs = ttlDurations.get(key);
        if (ttlMs == null || ttlMs <= 0) {
            return false;
        }
        long earlyWindowMs = ttlMs * stampedeProperties.getEarlyExpirePercent() / 100L;
        long remaining = deadline - now;
        if (remaining > earlyWindowMs) {
            return false;
        }
        // Probability rises linearly from 0 at the window start to 1 at expiry.
        double probability = 1.0 - (remaining / (double) Math.max(1, earlyWindowMs));
        return Math.random() < probability;
    }

    public void put(String key, Object value) {
        if (isEnabled() && value != null) {
            cache.put(key, value);
        }
    }

    /** Stores a value with an explicit TTL and jitter for stampede protection. */
    public void put(String key, String value, long ttlSeconds) {
        if (!isEnabled() || value == null) {
            return;
        }
        cache.put(key, value);
        long jittered = ttlSeconds * 1000L;
        if (stampedeProperties.isEnabled() && stampedeProperties.getJitterPercent() > 0) {
            long jitter = (long) (jittered * stampedeProperties.getJitterPercent() / 100.0);
            jittered += (long) (Math.random() * 2 * jitter) - jitter;
        }
        ttlDeadlines.put(key, System.currentTimeMillis() + jittered);
        ttlDurations.put(key, Math.max(1L, jittered));
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
                    put(key, (String) value, ttlSeconds);
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
            ttlDeadlines.remove(key);
            ttlDurations.remove(key);
        }
    }

    /**
     * Evicts every entry from the L1 cache. Used by distributed cache
     * invalidation when a pattern-based eviction arrives: Caffeine offers no
     * prefix eviction, and dropping a bounded (max 5000, TTL 60 s) cache
     * wholesale on a rare pattern invalidation is cheaper than maintaining a
     * per-key index.
     */
    public void clearAll() {
        if (isEnabled()) {
            cache.invalidateAll();
            ttlDeadlines.clear();
            ttlDurations.clear();
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