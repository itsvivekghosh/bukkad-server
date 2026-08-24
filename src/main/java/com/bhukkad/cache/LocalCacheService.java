package com.bhukkad.cache;

import com.bhukkad.config.LocalCacheProperties;
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
        // Check TTL deadline
        Long deadline = ttlDeadlines.get(key);
        if (deadline != null && System.currentTimeMillis() > deadline) {
            cache.invalidate(key);
            ttlDeadlines.remove(key);
            stats.merge("misses", 1L, Long::sum);
            return Optional.empty();
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