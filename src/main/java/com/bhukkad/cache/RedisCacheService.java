package com.bhukkad.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final String LOCK_PREFIX = "cache-lock:";
    private static final int LOCK_WAIT_RETRIES = 8;
    private static final long LOCK_WAIT_BASE_MS = 50L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public <T> T getOrCompute(String key, Class<T> type, long ttlSeconds, Supplier<T> supplier) {
        Optional<T> cached = get(key, type);
        if (cached.isPresent()) {
            return cached.get();
        }

        String lockKey = LOCK_PREFIX + key;
        if (tryAcquireLock(lockKey)) {
            try {
                cached = get(key, type);
                if (cached.isPresent()) {
                    return cached.get();
                }
                T value = supplier.get();
                if (value != null) {
                    set(key, value, ttlSeconds);
                }
                return value;
            } finally {
                delete(lockKey);
            }
        }

        return waitForValue(key, type, supplier);
    }

    public <T> List<T> getListOrCompute(String key, Class<T> type, long ttlSeconds, Supplier<List<T>> supplier) {
        Optional<List<T>> cached = getList(key, type);
        if (cached.isPresent()) {
            return cached.get();
        }

        String lockKey = LOCK_PREFIX + key;
        if (tryAcquireLock(lockKey)) {
            try {
                cached = getList(key, type);
                if (cached.isPresent()) {
                    return cached.get();
                }
                List<T> value = supplier.get();
                if (value != null) {
                    set(key, value, ttlSeconds);
                }
                return value != null ? value : List.of();
            } finally {
                delete(lockKey);
            }
        }

        return waitForList(key, type, supplier);
    }

    private boolean tryAcquireLock(String lockKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(buildKey(lockKey), "1", Duration.ofSeconds(10)));
        } catch (Exception e) {
            log.warn("CACHE_LOCK_FAILED key={} error={}", lockKey, e.getMessage());
            return false;
        }
    }

    private <T> T waitForValue(String key, Class<T> type, Supplier<T> supplier) {
        for (int attempt = 0; attempt < LOCK_WAIT_RETRIES; attempt++) {
            sleepBackoff(attempt);
            Optional<T> cached = get(key, type);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        return supplier.get();
    }

    private <T> List<T> waitForList(String key, Class<T> type, Supplier<List<T>> supplier) {
        for (int attempt = 0; attempt < LOCK_WAIT_RETRIES; attempt++) {
            sleepBackoff(attempt);
            Optional<List<T>> cached = getList(key, type);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        List<T> value = supplier.get();
        return value != null ? value : List.of();
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(LOCK_WAIT_BASE_MS * (attempt + 1L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== BASIC OPERATIONS ====================

    public void set(String key, Object value, long ttlSeconds) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.opsForValue().set(fullKey, value, Duration.ofSeconds(ttlSeconds));
            log.debug("CACHE_SET key={} ttl={}s", fullKey, ttlSeconds);
        } catch (Exception e) {
            log.warn("CACHE_SET_FAILED key={} error={}", key, e.getMessage());
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String fullKey = buildKey(key);
            Object value = redisTemplate.opsForValue().get(fullKey);

            if (value != null) {
                log.debug("CACHE_HIT key={}", fullKey);
                T result = objectMapper.convertValue(value, type);
                return Optional.of(result);
            }

            log.debug("CACHE_MISS key={}", fullKey);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("CACHE_GET_FAILED key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<List<T>> getList(String key, Class<T> type) {
        try {
            String fullKey = buildKey(key);
            Object value = redisTemplate.opsForValue().get(fullKey);

            if (value != null) {
                log.debug("CACHE_HIT key={}", fullKey);
                List<T> result = objectMapper.convertValue(value,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, type));
                return Optional.of(result);
            }

            log.debug("CACHE_MISS key={}", fullKey);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("CACHE_GET_LIST_FAILED key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String key) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.delete(fullKey);
            log.debug("CACHE_DELETE key={}", fullKey);
        } catch (Exception e) {
            log.warn("CACHE_DELETE_FAILED key={} error={}", key, e.getMessage());
        }
    }

    public void deletePattern(String pattern) {
        try {
            String fullPattern = buildKey(pattern) + "*";
            Set<String> keys = redisTemplate.keys(fullPattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("CACHE_DELETE_PATTERN pattern={} count={}", fullPattern, keys.size());
            }
        } catch (Exception e) {
            log.warn("CACHE_DELETE_PATTERN_FAILED pattern={} error={}", pattern, e.getMessage());
        }
    }

    public boolean exists(String key) {
        try {
            String fullKey = buildKey(key);
            return Boolean.TRUE.equals(redisTemplate.hasKey(fullKey));
        } catch (Exception e) {
            return false;
        }
    }

    public void setExpiry(String key, long ttlSeconds) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.expire(fullKey, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("CACHE_EXPIRY_FAILED key={} error={}", key, e.getMessage());
        }
    }

    // ==================== HASH OPERATIONS ====================

    public void hSet(String key, String field, Object value) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.opsForHash().put(fullKey, field, value);
            log.debug("CACHE_HSET key={} field={}", fullKey, field);
        } catch (Exception e) {
            log.warn("CACHE_HSET_FAILED key={} field={} error={}", key, field, e.getMessage());
        }
    }

    public <T> Optional<T> hGet(String key, String field, Class<T> type) {
        try {
            String fullKey = buildKey(key);
            Object value = redisTemplate.opsForHash().get(fullKey, field);
            if (value != null) {
                return Optional.of(objectMapper.convertValue(value, type));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("CACHE_HGET_FAILED key={} field={} error={}", key, field, e.getMessage());
            return Optional.empty();
        }
    }

    public void hDelete(String key, String... fields) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.opsForHash().delete(fullKey, (Object[]) fields);
        } catch (Exception e) {
            log.warn("CACHE_HDEL_FAILED key={} error={}", key, e.getMessage());
        }
    }

    // ==================== COUNTER OPERATIONS ====================

    public Long increment(String key) {
        try {
            String fullKey = buildKey(key);
            return redisTemplate.opsForValue().increment(fullKey);
        } catch (Exception e) {
            log.warn("CACHE_INCREMENT_FAILED key={} error={}", key, e.getMessage());
            return null;
        }
    }

    // ==================== CACHE MANAGEMENT ====================

    public void clearAll() {
        try {
            Set<String> keys = redisTemplate.keys("bhukkad:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("CACHE_CLEAR_ALL count={}", keys.size());
            }
        } catch (Exception e) {
            log.error("CACHE_CLEAR_ALL_FAILED error={}", e.getMessage());
        }
    }

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            Set<String> keys = redisTemplate.keys("bhukkad:*");
            stats.put("totalKeys", keys != null ? keys.size() : 0);

            // Count by prefix
            Map<String, Integer> keyCounts = new HashMap<>();
            if (keys != null) {
                for (String key : keys) {
                    String prefix = key.split(":").length > 1 ? key.split(":")[1] : "other";
                    keyCounts.merge(prefix, 1, Integer::sum);
                }
            }
            stats.put("keysByType", keyCounts);
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    // ==================== KEY BUILDER ====================

    private String buildKey(String key) {
        if (key.startsWith(CacheConstants.KEY_PREFIX)) return key;
        return CacheConstants.KEY_PREFIX + key;
    }
}