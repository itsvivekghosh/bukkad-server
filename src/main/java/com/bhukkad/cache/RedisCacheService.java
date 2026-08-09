package com.bhukkad.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
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