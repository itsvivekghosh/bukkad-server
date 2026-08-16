package com.bhukkad.cache;

import com.bhukkad.config.LocalCacheProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocalCacheService {

    private final LocalCacheProperties properties;
    private final Cache<String, Object> cache;
    private final Map<String, Long> stats = new ConcurrentHashMap<>();

    public LocalCacheService(LocalCacheProperties properties) {
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getTtlSeconds()))
                .recordStats()
                .build();
        stats.put("hits", 0L);
        stats.put("misses", 0L);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        if (!isEnabled()) {
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

    public void invalidate(String key) {
        if (isEnabled()) {
            cache.invalidate(key);
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
