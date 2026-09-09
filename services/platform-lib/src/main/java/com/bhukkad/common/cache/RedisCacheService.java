package com.bhukkad.common.cache;

import com.bhukkad.common.cache.DistributedCacheInvalidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Extended Redis cache service that publishes invalidation events for
 * distributed cache coherence across multiple application instances.
 */
@Service
@ConditionalOnBean(name = "redisTemplate")
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final String LOCK_PREFIX = "cache-lock:";
    private static final int LOCK_WAIT_RETRIES = 10;
    private static final long LOCK_WAIT_BASE_MS = 20L;
    private static final long LOCK_TTL_SECONDS = 10L;
    private static final double TTL_JITTER_PERCENT = 0.10;

    /**
     * Atomically releases the lock only if we still own it. The lock value is a
     * per-acquisition UUID token stored via {@link StringRedisTemplate} (raw
     * bytes), so the Lua {@code GET} comparison against {@code ARGV[1]} is exact.
     * Releasing with a stale token (because the lock expired and another thread
     * re-acquired it) is a no-op, which prevents the "lock theft" race where a
     * slow {@code finally} deletes a lock that no longer belongs to it.
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final LocalCacheService localCacheService;
    private final DistributedCacheInvalidator distributedInvalidator;

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate,
                             StringRedisTemplate stringRedisTemplate,
                             ObjectMapper objectMapper,
                             LocalCacheService localCacheService,
                             DistributedCacheInvalidator distributedInvalidator) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.localCacheService = localCacheService;
        this.distributedInvalidator = distributedInvalidator;
    }

    // ==================== CACHE-ASIDE READ PATH ====================

    public <T> T getOrCompute(String key, Class<T> type, long ttlSeconds, Supplier<T> supplier) {
        Optional<T> l1 = localCacheService.get(key, type);
        if (l1.isPresent()) {
            return l1.get();
        }

        Optional<T> cached = get(key, type);
        if (cached.isPresent()) {
            return cached.get();
        }

        String lockKey = LOCK_PREFIX + key;
        String lockToken = tryAcquireLock(lockKey);
        if (lockToken != null) {
            try {
                cached = get(key, type);
                if (cached.isPresent()) {
                    return cached.get();
                }
                T value = supplier.get();
                if (value != null) {
                    set(key, value, ttlSeconds);
                    localCacheService.put(key, value);
                }
                return value;
            } finally {
                releaseLock(lockKey, lockToken);
            }
        }

        return waitForValue(key, type, supplier);
    }

    public <T> List<T> getListOrCompute(String key, Class<T> type, long ttlSeconds, Supplier<List<T>> supplier) {
        @SuppressWarnings("unchecked")
        Optional<List<T>> l1 = (Optional<List<T>>) (Optional<?>) localCacheService.get(key, List.class);
        if (l1.isPresent()) {
            return l1.get();
        }

        Optional<List<T>> cached = getList(key, type);
        if (cached.isPresent()) {
            return cached.get();
        }

        String lockKey = LOCK_PREFIX + key;
        String lockToken = tryAcquireLock(lockKey);
        if (lockToken != null) {
            try {
                cached = getList(key, type);
                if (cached.isPresent()) {
                    return cached.get();
                }
                List<T> value = supplier.get();
                if (value != null) {
                    set(key, value, ttlSeconds);
                    localCacheService.put(key, value);
                }
                return value != null ? value : List.of();
            } finally {
                releaseLock(lockKey, lockToken);
            }
        }

        return waitForList(key, type, supplier);
    }

    // ==================== INVALIDATION (WITH DISTRIBUTED PUBLISH) ====================

    public void delete(String key) {
        String cacheName = extractCacheName(key);
        try {
            String fullKey = buildKey(key);
            redisTemplate.delete(fullKey);
            localCacheService.invalidate(key);
            log.debug("CACHE_DELETE key={}", fullKey);
        } catch (Exception e) {
            log.warn("CACHE_DELETE_FAILED key={} error={}", key, e.getMessage());
        } finally {
            publishInvalidation(cacheName, key, false);
        }
    }

    public void deletePattern(String pattern) {
        String cacheName = extractCacheName(pattern);
        try {
            String fullPattern = buildKey(pattern) + "*";
            // SCAN instead of KEYS: KEYS blocks Redis for the duration of the
            // scan and is O(N) on the whole keyspace; SCAN iterates incrementally
            // in bounded batches and does not block concurrent traffic.
            Set<String> keys = scanKeys(fullPattern);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("CACHE_DELETE_PATTERN pattern={} count={}", fullPattern, keys.size());
            }
        } catch (Exception e) {
            log.warn("CACHE_DELETE_PATTERN_FAILED pattern={} error={}", pattern, e.getMessage());
        } finally {
            publishInvalidation(cacheName, pattern, true);
        }
    }

    private void publishInvalidation(String cacheName, String key, boolean pattern) {
        if (distributedInvalidator != null) {
            distributedInvalidator.publishInvalidation(cacheName, key, pattern);
        }
    }

    private String extractCacheName(String keyOrPattern) {
        String clean = keyOrPattern.startsWith(CacheConstants.KEY_PREFIX)
                ? keyOrPattern.substring(CacheConstants.KEY_PREFIX.length())
                : keyOrPattern;
        int sep = clean.indexOf(CacheConstants.KEY_SEPARATOR);
        return sep > 0 ? clean.substring(0, sep) : clean;
    }

    // ==================== BASIC OPERATIONS ====================

    public void set(String key, Object value, long ttlSeconds) {
        try {
            String fullKey = buildKey(key);
            long jitteredTtl = ttlSeconds;
            if (ttlSeconds > 10) {
                long jitter = (long) (ttlSeconds * TTL_JITTER_PERCENT);
                long delta = java.util.concurrent.ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
                jitteredTtl = Math.max(10, ttlSeconds + delta);
            }
            redisTemplate.opsForValue().set(fullKey, value, Duration.ofSeconds(jitteredTtl));
            log.debug("CACHE_SET key={} ttl={}s (jittered from {}s)", fullKey, jitteredTtl, ttlSeconds);
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
                localCacheService.put(key, result);
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
                localCacheService.put(key, result);
                return Optional.of(result);
            }

            log.debug("CACHE_MISS key={}", fullKey);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("CACHE_GET_LIST_FAILED key={} error={}", key, e.getMessage());
            return Optional.empty();
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
            Set<String> keys = scanKeys(CacheConstants.KEY_PREFIX + "*");
            if (!keys.isEmpty()) {
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
            Set<String> keys = scanKeys(CacheConstants.KEY_PREFIX + "*");
            stats.put("totalKeys", keys.size());

            Map<String, Integer> keyCounts = new HashMap<>();
            for (String key : keys) {
                String prefix = key.split(":").length > 1 ? key.split(":")[1] : "other";
                keyCounts.merge(prefix, 1, Integer::sum);
            }
            stats.put("keysByType", keyCounts);
            stats.put("localCache", localCacheService.getStats());
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    // ==================== HELPERS ====================

    /**
     * Attempts to acquire the single-flight lock for {@code lockKey}. Returns
     * the unique ownership token on success, or {@code null} when the lock is
     * held by another thread or Redis is unavailable (the caller then falls
     * through to {@link #waitForValue}).
     */
    private String tryAcquireLock(String lockKey) {
        try {
            String token = UUID.randomUUID().toString();
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(buildKey(lockKey), token, Duration.ofSeconds(LOCK_TTL_SECONDS));
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            log.warn("CACHE_LOCK_FAILED key={} error={}", lockKey, e.getMessage());
            return null;
        }
    }

    /**
     * Releases the single-flight lock only if {@code token} still owns it (see
     * {@link #RELEASE_LOCK_SCRIPT}). A stale token — the lock expired while the
     * supplier was running and another thread re-acquired it — is a no-op, so a
     * slow {@code finally} can never delete another thread's lock.
     */
    private void releaseLock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(buildKey(lockKey)), token);
        } catch (Exception e) {
            log.warn("CACHE_UNLOCK_FAILED key={} error={}", lockKey, e.getMessage());
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
            // Exponential backoff: 20,40,80,160... with jitter to avoid synchronized retry
            long base = LOCK_WAIT_BASE_MS * (1L << Math.min(attempt, 6));
            long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 10);
            Thread.sleep(Math.min(base + jitter, 500));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Incrementally iterates the keyspace using Redis SCAN, which returns keys
     * in bounded batches without blocking the server. This is the safe
     * replacement for {@code RedisTemplate.keys(pattern)} (the KEYS command),
     * which blocks Redis for the whole scan and should never be used in
     * production.
     *
     * @param pattern glob pattern to match, e.g. {@code bhukkad:restaurant:list:*}
     * @return matching keys, empty set on any failure
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(200).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("CACHE_SCAN_FAILED pattern={} error={}", pattern, e.getMessage());
        }
        return keys;
    }

    private String buildKey(String key) {
        if (key.startsWith(CacheConstants.KEY_PREFIX)) return key;
        return CacheConstants.KEY_PREFIX + key;
    }
}
