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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Extended Redis cache service that publishes invalidation events for
 * distributed cache coherence across multiple application instances.
 *
 * <p>PERF-3: concurrent misses inside one JVM are coalesced with a per-key
 * {@link CompletableFuture} (single-flight): the first thread computes, the
 * rest join the same future for at most {@value #INFLIGHT_WAIT_CAP_MS} ms and
 * otherwise fall through to the L2 read or their own supplier. The previous
 * implementation parked losing callers in a {@code Thread.sleep} backoff loop
 * of up to ~3.5 s, so adopting the cache on a hot path would have turned every
 * cold entry into a p99 cliff; that parking is gone. The distributed Redis lock
 * (cross-pod compute coalescing), the jittered Redis TTL, the probabilistic
 * early-expiry and the L1 structure are kept unchanged.</p>
 */
@Service
@ConditionalOnBean(name = "redisTemplate")
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final String LOCK_PREFIX = "cache-lock:";
    private static final long LOCK_TTL_SECONDS = 10L;
    private static final double TTL_JITTER_PERCENT = 0.10;

    /**
     * Maximum time a thread joining an in-JVM single-flight may wait for the
     * computing thread before falling back to an L2 read / its own supplier.
     */
    private static final long INFLIGHT_WAIT_CAP_MS = 250L;

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

    /**
     * In-JVM single-flight map: one entry per key currently being computed by
     * the cache-aside path. Losing threads join the leader's future instead of
     * re-running the supplier or parking on sleeps. The flight key is prefixed
     * ({@code v:}/{@code l:}) so the single-value and list variants of the same
     * cache key never share a future whose value has a different shape.
     */
    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlightLoads =
            new ConcurrentHashMap<>();

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

        // In-JVM single-flight: exactly one thread per key computes the value;
        // concurrent misses join the same future (bounded by INFLIGHT_WAIT_CAP_MS)
        // and otherwise fall through to an L2 read or their own supplier.
        String flightKey = "v:" + key;
        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture<T> leader = registerOrJoinFlight(flightKey, future);
        if (leader != null) {
            return joinSingleFlight(leader, () -> get(key, type).orElse(null), supplier);
        }
        try {
            T computed = computeUnderDistributedLock(key, ttlSeconds, () -> get(key, type), supplier);
            future.complete(computed);
            return computed;
        } catch (RuntimeException | Error ex) {
            future.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlightLoads.remove(flightKey, future);
        }
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

        String flightKey = "l:" + key;
        CompletableFuture<List<T>> future = new CompletableFuture<>();
        CompletableFuture<List<T>> leader = registerOrJoinFlight(flightKey, future);
        if (leader != null) {
            return joinSingleFlight(leader, () -> getList(key, type).orElse(null),
                    () -> normaliseList(supplier.get()));
        }
        try {
            List<T> computed = normaliseList(computeUnderDistributedLock(
                    key, ttlSeconds, () -> getList(key, type), supplier));
            future.complete(computed);
            return computed;
        } catch (RuntimeException | Error ex) {
            future.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlightLoads.remove(flightKey, future);
        }
    }

    /**
     * Tries to claim the in-flight slot for {@code flightKey}. Returns
     * {@code null} when the caller becomes the leader (it must compute and
     * complete {@code future}), or the existing leader's future to join. The
     * flight key is variant-prefixed ({@code v:}/{@code l:}) so the single-value
     * and list variants of the same cache key never share a differently-typed
     * future.
     */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> registerOrJoinFlight(String flightKey, CompletableFuture<T> future) {
        CompletableFuture<?> existing = inFlightLoads.putIfAbsent(flightKey, future);
        return existing == null ? null : (CompletableFuture<T>) existing;
    }

    /**
     * Leader computation. Holds the distributed Redis lock best-effort so pods
     * coalesce onto one database read; on contention or a Redis outage the
     * value is still served — a cheap L2 re-read first, then the supplier.
     * Losing the lock must never park the caller (the pre-PERF-3 behaviour
     * slept up to ~3.5 s here).
     */
    private <T> T computeUnderDistributedLock(String key, long ttlSeconds,
                                              Supplier<Optional<T>> l2Reader, Supplier<T> supplier) {
        String lockKey = LOCK_PREFIX + key;
        String lockToken = tryAcquireLock(lockKey);
        if (lockToken != null) {
            try {
                Optional<T> cached = l2Reader.get();
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
        // Lock held by another pod, or Redis is unavailable: fall through to
        // L2, then to the supplier itself (never park; the pre-PERF-3 sleep
        // loop added up to ~3.5 s to this exact path).
        Optional<T> cached = l2Reader.get();
        return cached.isPresent() ? cached.get() : supplier.get();
    }

    /**
     * Awaits the leader's future for at most {@value #INFLIGHT_WAIT_CAP_MS} ms.
     * Timeout, leader failure, or interruption each fall through to one final
     * L2 read and then the caller's own supplier.
     */
    private <T> T joinSingleFlight(CompletableFuture<T> leader, Supplier<T> l2Reader, Supplier<T> supplier) {
        try {
            return leader.get(INFLIGHT_WAIT_CAP_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failedLeader) {
            log.warn("CACHE_LEADER_FAILED error={}", String.valueOf(failedLeader.getCause()));
        } catch (TimeoutException slowLeader) {
            // bounded: never wait past the cap for a slow leader
        }
        T cached = l2Reader.get();
        return cached != null ? cached : supplier.get();
    }

    private <T> List<T> normaliseList(List<T> value) {
        return value != null ? value : List.of();
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
     * held by another pod or Redis is unavailable (the caller then falls
     * through to a direct L2 read / supplier without waiting).
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
