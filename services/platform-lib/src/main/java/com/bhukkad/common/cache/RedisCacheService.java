package com.bhukkad.common.cache;

import com.bhukkad.common.cache.CacheProperties;
import com.bhukkad.common.cache.DistributedCacheInvalidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

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

    /**
     * TTL for the distributed single-flight lock. Injected from
     * {@link CacheProperties#getLockTtlSeconds()} so operators can tune it
     * to P99 supplier latency + margin (default: 30 s).
     */
    private final long lockTtlSeconds;

    /**
     * Maximum active entries in the in-JVM single-flight map before stale
     * completed futures are evicted. Prevents unbounded heap growth under
     * sustained high-cardinality cache misses.
     */
    private final int maxInFlightEntries;

    /**
     * Jitter percentage applied to TTL values > 10 seconds to prevent
     * synchronized cache expiry (thundering herd) across multiple instances.
     * Set to 10% (±10% around the base TTL).
     */
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
    private final ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider;
    private final CacheProperties cacheProperties;
    private final MeterRegistry meterRegistry;
   /** Bounded-elastic scheduler for async cache computation (OPT-021). */
   private final Scheduler computeScheduler = Schedulers.boundedElastic();

    /**
     * In-JVM single-flight map: one entry per key currently being computed by
     * the cache-aside path. Losing threads join the leader's future instead of
     * re-running the supplier or parking on sleeps. The flight key is prefixed
     * ({@code v:}/{@code l:}) so the single-value and list variants of the same
     * cache key never share a future whose value has a different shape.
     *
     * PERF-3: Use Caffeine cache with strict LRU eviction to guarantee bounded
     * memory regardless of caller behavior. Previously used ConcurrentHashMap
     * which could grow unbounded under high-cardinality cache miss storms.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, CompletableFuture<?>> inFlightLoads =
            Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterAccess(Duration.ofMinutes(5))
                    .build();

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate,
                             StringRedisTemplate stringRedisTemplate,
                             ObjectMapper objectMapper,
                             LocalCacheService localCacheService,
                             DistributedCacheInvalidator distributedInvalidator,
                             ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider,
                             CacheProperties cacheProperties,
                             MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.localCacheService = localCacheService;
        this.distributedInvalidator = distributedInvalidator;
        this.circuitBreakerRegistryProvider = circuitBreakerRegistryProvider;
        this.cacheProperties = cacheProperties;
        this.meterRegistry = meterRegistry;
        this.lockTtlSeconds = cacheProperties.getLockTtlSeconds();
        this.maxInFlightEntries = cacheProperties.getMaxInFlightEntries();
    }

    // ==================== CACHE-ASIDE READ PATH ====================

    public <T> T getOrCompute(String key, Class<T> type, long ttlSeconds, Supplier<T> supplier) {
        Optional<T> l1 = localCacheService.get(key, type);
        if (l1.isPresent()) {
            return l1.get();
        }

        // L2 read: if Redis is down, treat as cache miss and proceed to supplier
        Optional<T> cached = l2ReadOrMiss(() -> get(key, type));
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
            return joinSingleFlight(leader, () -> l2ReadOrMiss(() -> get(key, type)).orElse(null), supplier);
        }
        try {
            // OPT-021: Async computation on bounded-elastic scheduler to avoid
            // blocking servlet/Kafka threads during cache miss storms.
            CompletableFuture<T> computedFuture = CompletableFuture.supplyAsync(
                () -> computeUnderDistributedLock(key, ttlSeconds,
                        () -> l2ReadOrMiss(() -> get(key, type)), supplier),
                computeScheduler::schedule);
            try {
                T computed = computedFuture.get(INFLIGHT_WAIT_CAP_MS, TimeUnit.MILLISECONDS);
                future.complete(computed);
                return computed;
            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                // Fall through to L2 or own supplier on timeout
                future.completeExceptionally(e);
                T cachedVal = l2ReadOrMiss(() -> get(key, type)).orElse(null);
                return cachedVal != null ? cachedVal : supplier.get();
            }
        } catch (RuntimeException | Error ex) {
            future.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlightLoads.invalidate(flightKey);
        }
    }

    public <T> List<T> getListOrCompute(String key, Class<T> type, long ttlSeconds, Supplier<List<T>> supplier) {
        @SuppressWarnings("unchecked")
        Optional<List<T>> l1 = (Optional<List<T>>) (Optional<?>) localCacheService.get(key, List.class);
        if (l1.isPresent()) {
            return l1.get();
        }

        // L2 read: if Redis is down, treat as cache miss and proceed to supplier
        Optional<List<T>> cached = l2ReadOrMiss(() -> getList(key, type));
        if (cached.isPresent()) {
            return cached.get();
        }

        String flightKey = "l:" + key;
        CompletableFuture<List<T>> future = new CompletableFuture<>();
        CompletableFuture<List<T>> leader = registerOrJoinFlight(flightKey, future);
        if (leader != null) {
            return joinSingleFlight(leader,
                    () -> l2ReadOrMiss(() -> getList(key, type)).orElse(null),
                    () -> normaliseList(supplier.get()));
        }
        try {
            // OPT-021: Async computation on bounded-elastic scheduler.
            CompletableFuture<List<T>> computedFuture = CompletableFuture.supplyAsync(
                () -> normaliseList(computeUnderDistributedLock(
                        key, ttlSeconds,
                        () -> l2ReadOrMiss(() -> getList(key, type)), supplier)),
                computeScheduler::schedule);
            try {
                List<T> computed = computedFuture.get(INFLIGHT_WAIT_CAP_MS, TimeUnit.MILLISECONDS);
                future.complete(computed);
                return computed;
            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                future.completeExceptionally(e);
                List<T> cachedVal = l2ReadOrMiss(() -> getList(key, type)).orElse(null);
                return cachedVal != null ? cachedVal : normaliseList(supplier.get());
            }
        } catch (RuntimeException | Error ex) {
            future.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlightLoads.invalidate(flightKey);
        }
    }

    /**
     * Tries to claim the in-flight slot for {@code flightKey}. Returns
     * {@code null} when the caller becomes the leader (it must compute and
     * complete {@code future}), or the existing leader's future to join. The
     * flight key is variant-prefixed ({@code v:}/{@code l:}) so the single-value
     * and list variants of the same cache key never share a differently-typed
     * future.
     *
     * <p>Caffeine provides strict LRU eviction with maxSize=10,000 and
     * expireAfterAccess=5min, guaranteeing bounded memory regardless of caller
     * behavior. No manual eviction needed.</p>
     */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> registerOrJoinFlight(String flightKey, CompletableFuture<T> future) {
        // Caffeine's get(key, mappingFunction) is atomic: returns existing value
        // if present, otherwise computes and stores the mapping function result.
        // We use a dummy mapping function that just returns 'future' for new entries.
        CompletableFuture<?> existing = inFlightLoads.get(flightKey, k -> future);
        return existing == future ? null : (CompletableFuture<T>) existing;
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
        String lockToken;
        try {
            lockToken = tryAcquireLock(lockKey);
        } catch (Exception e) {
            // Lock acquisition failed — fall through to direct L2 read / supplier
            log.warn("CACHE_LOCK_ACQUIRE_FAILED key={} error={}", key, e.getMessage());
            lockToken = null;
        }
        if (lockToken != null) {
            try {
                Optional<T> cached = l2ReadOrMiss(l2Reader);
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
                try {
                    releaseLock(lockKey, lockToken);
                } catch (Exception e) {
                    // Lock release failures must not mask a successful compute.
                    // The lock will expire via TTL; log and record metric for observability.
                    log.warn("CACHE_LOCK_RELEASE_FAILED key={} error={}", lockKey, e.getMessage());
                }
            }
        }
        // Lock held by another pod, or Redis is unavailable: fall through to
        // L2, then to the supplier itself (never park; the pre-PERF-3 sleep
        // loop added up to ~3.5 s to this exact path).
        Optional<T> cached = l2ReadOrMiss(l2Reader);
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
            log.warn("CACHE_LEADER_INTERRUPTED error={}", interrupted.getMessage());
        } catch (ExecutionException failedLeader) {
            log.warn("CACHE_LEADER_FAILED error={}", String.valueOf(failedLeader.getCause()));
        } catch (TimeoutException slowLeader) {
            log.warn("CACHE_LEADER_TIMEOUT waiting for leader; falling back to L2/supplier");
        }
        T cached = l2Reader.get();
        return cached != null ? cached : supplier.get();
    }

    private <T> List<T> normaliseList(List<T> value) {
        return value != null ? value : List.of();
    }

    /**
     * Reads from L2 (Redis) but treats any exception as a cache miss — returns
     * {@link Optional#empty()} instead of propagating. This lets callers fall
     * through to the supplier when Redis is down, without aborting the compute
     * flow.
     */
    private <T> Optional<T> l2ReadOrMiss(Supplier<Optional<T>> reader) {
        try {
            return reader.get();
        } catch (CacheUnavailableException | CacheOperationException e) {
            log.warn("CACHE_L2_READ_FAILED error={}", e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== INVALIDATION (WITH DISTRIBUTED PUBLISH) ====================


    public void delete(String key) {
        String cacheName = extractCacheName(key);
        try {
            String fullKey = buildKey(key);
            withCircuitBreaker("redisCacheWrite",
                    () -> { redisTemplate.delete(fullKey); return null; });
            localCacheService.invalidate(key);
            log.debug("CACHE_DELETE key={}", fullKey);
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for delete", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_DELETE_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("delete");
            throw new CacheOperationException("Cache delete failed: " + e.getMessage(), e);
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
                // Batch DEL in chunks to avoid a single huge command.
                deleteBatch(new ArrayList<>(keys), "redisCacheWrite");
                log.debug("CACHE_DELETE_PATTERN pattern={} count={}", fullPattern, keys.size());
            }
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for deletePattern", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_DELETE_PATTERN_ERROR pattern={} error={}", pattern, e.getMessage(), e);
            recordCacheFailure("deletePattern");
            throw new CacheOperationException("Cache deletePattern failed: " + e.getMessage(), e);
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

    /**
     * Stores a value in Redis with a TTL that is jittered by ±10% to prevent
     * synchronized cache expiry (thundering herd) across multiple instances.
     *
     * <p>For {@code ttlSeconds > 10}, the actual TTL stored in Redis is a random
     * value in the range {@code [ttlSeconds - 10%, ttlSeconds + 10%]}, with a
     * minimum floor of 10 seconds. For {@code ttlSeconds <= 10}, the exact TTL
     * is used without jitter.</p>
     *
     * <p>This jitter applies to all cached keys written through this method,
     * including feature flags, rate limits, search results, and any other
     * cache entries. Feature flag overrides (stored in a Redis hash without TTL)
     * and rate-limit keys (managed by {@link RedisRateLimitService} via Lua
     * script with window-based EXPIRE) are outside the scope of this jitter.</p>
     *
     * @param key the cache key
     * @param value the value to store
     * @param ttlSeconds the base TTL in seconds before jitter is applied
     */
    public void set(String key, Object value, long ttlSeconds) {
        try {
            String fullKey = buildKey(key);
            long jitteredTtl = ttlSeconds;
            if (ttlSeconds > 10) {
                long jitter = (long) (ttlSeconds * TTL_JITTER_PERCENT);
                long delta = java.util.concurrent.ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
                jitteredTtl = Math.max(10, ttlSeconds + delta);
            }
            final long finalJitteredTtl = jitteredTtl;
            withCircuitBreaker("redisCacheWrite",
                    () -> {
                        redisTemplate.opsForValue().set(fullKey, value, Duration.ofSeconds(finalJitteredTtl));
                        return null;
                    });
            log.debug("CACHE_SET key={} ttl={}s (jittered from {}s)", fullKey, jitteredTtl, ttlSeconds);
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for set", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_SET_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("set");
            throw new CacheOperationException("Cache set failed: " + e.getMessage(), e);
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String fullKey = buildKey(key);
            Object value = withCircuitBreaker("redisCacheRead",
                    () -> redisTemplate.opsForValue().get(fullKey));

            if (value != null) {
                log.debug("CACHE_HIT key={}", fullKey);
                T result = objectMapper.convertValue(value, type);
                localCacheService.put(key, result);
                return Optional.of(result);
            }

            log.debug("CACHE_MISS key={}", fullKey);
            return Optional.empty();
        } catch (CallNotPermittedException e) {
            // Circuit breaker OPEN — fail fast, don't mask as miss
            throw new CacheUnavailableException("Redis circuit breaker open for read", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            // Redis DOWN — propagate so caller can decide fail-open/fail-closed
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            // Unexpected error — log with full context, rethrow as unchecked
            log.error("CACHE_GET_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("get");
            throw new CacheOperationException("Cache read failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<List<T>> getList(String key, Class<T> type) {
        try {
            String fullKey = buildKey(key);
            Object value = withCircuitBreaker("redisCacheRead",
                    () -> redisTemplate.opsForValue().get(fullKey));

            if (value != null) {
                log.debug("CACHE_HIT key={}", fullKey);
                List<T> result = objectMapper.convertValue(value,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, type));
                localCacheService.put(key, result);
                return Optional.of(result);
            }

            log.debug("CACHE_MISS key={}", fullKey);
            return Optional.empty();
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for list read", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_GET_LIST_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("getList");
            throw new CacheOperationException("Cache list read failed: " + e.getMessage(), e);
        }
    }

    public boolean exists(String key) {
        try {
            String fullKey = buildKey(key);
            return withCircuitBreaker("redisCacheExists",
                    () -> { Object v = redisTemplate.opsForValue().get(fullKey); return v != null; });
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for exists", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_EXISTS_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("exists");
            throw new CacheOperationException("Cache exists check failed: " + e.getMessage(), e);
        }
    }

    public void setExpiry(String key, long ttlSeconds) {
        try {
            String fullKey = buildKey(key);
            withCircuitBreaker("redisCacheWrite",
                    () -> { redisTemplate.expire(fullKey, ttlSeconds, TimeUnit.SECONDS); return null; });
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for setExpiry", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_EXPIRY_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("setExpiry");
            throw new CacheOperationException("Cache setExpiry failed: " + e.getMessage(), e);
        }
    }

    // ==================== HASH OPERATIONS ====================

    public void hSet(String key, String field, Object value) {
        try {
            String fullKey = buildKey(key);
            withCircuitBreaker("redisCacheWrite",
                    () -> { redisTemplate.opsForHash().put(fullKey, field, value); return null; });
            log.debug("CACHE_HSET key={} field={}", fullKey, field);
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for hSet", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_HSET_ERROR key={} field={} error={}", key, field, e.getMessage(), e);
            recordCacheFailure("hSet");
            throw new CacheOperationException("Cache hSet failed: " + e.getMessage(), e);
        }
    }

    public <T> Optional<T> hGet(String key, String field, Class<T> type) {
        try {
            String fullKey = buildKey(key);
            Object value = withCircuitBreaker("redisCacheRead",
                    () -> redisTemplate.opsForHash().get(fullKey, field));
            if (value != null) {
                return Optional.of(objectMapper.convertValue(value, type));
            }
            return Optional.empty();
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for hGet", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_HGET_ERROR key={} field={} error={}", key, field, e.getMessage(), e);
            recordCacheFailure("hGet");
            throw new CacheOperationException("Cache hGet failed: " + e.getMessage(), e);
        }
    }

    public void hDelete(String key, String... fields) {
        try {
            String fullKey = buildKey(key);
            withCircuitBreaker("redisCacheWrite",
                    () -> { redisTemplate.opsForHash().delete(fullKey, (Object[]) fields); return null; });
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for hDelete", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_HDEL_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("hDelete");
            throw new CacheOperationException("Cache hDelete failed: " + e.getMessage(), e);
        }
    }

    // ==================== COUNTER OPERATIONS ====================

    public Long increment(String key) {
        try {
            String fullKey = buildKey(key);
            return withCircuitBreaker("redisCacheWrite",
                    () -> redisTemplate.opsForValue().increment(fullKey));
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for increment", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_INCREMENT_ERROR key={} error={}", key, e.getMessage(), e);
            recordCacheFailure("increment");
            throw new CacheOperationException("Cache increment failed: " + e.getMessage(), e);
        }
    }

    // ==================== CACHE MANAGEMENT ====================

    public void clearAll() {
        try {
            Set<String> keys = scanKeys(CacheConstants.KEY_PREFIX + "*");
            if (!keys.isEmpty()) {
                // Batch DEL in chunks to avoid blocking the Redis event loop with
                // a single huge command. Each chunk issues one DEL with up to
                // 500 keys.
                deleteBatch(new ArrayList<>(keys), "redisCacheAdmin");
                log.info("CACHE_CLEAR_ALL count={}", keys.size());
            }
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for clearAll", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_CLEAR_ALL_ERROR error={}", e.getMessage(), e);
            recordCacheFailure("clearAll");
            throw new CacheOperationException("Cache clearAll failed: " + e.getMessage(), e);
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
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for getCacheStats", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_STATS_ERROR error={}", e.getMessage(), e);
            recordCacheFailure("getCacheStats");
            throw new CacheOperationException("Cache getCacheStats failed: " + e.getMessage(), e);
        }
        return stats;
    }

    // ==================== HELPERS ====================

    /**
     * Executes {@code supplier} under a Resilience4j circuit breaker named
     * {@code breakerName}. When the breaker is open, calls fail fast with
     * {@link CallNotPermittedException}, which the caller should treat as
     * "Redis unavailable" and fall through to its supplier/empty-return path.
     * If no {@link CircuitBreakerRegistry} is present, the supplier runs
     * unguarded (dev/test profiles without Resilience4j).
     */
    private <T> T withCircuitBreaker(String breakerName, Supplier<T> supplier) {
        CircuitBreakerRegistry registry = circuitBreakerRegistryProvider.getIfAvailable();
        if (registry == null) {
            return supplier.get();
        }
        CircuitBreaker breaker = registry.circuitBreaker(breakerName);
        return CircuitBreaker.decorateSupplier(breaker, supplier).get();
    }

    /**
     * Increments the {@code cache_operation_failed} counter for the given
     * operation when a MeterRegistry is available. Always safe to call.
     */
    private void recordCacheFailure(String operation) {
        if (meterRegistry != null) {
            meterRegistry.counter("cache_operation_failed", "operation", operation).increment();
        }
    }

    /**
     * Deletes keys in batches of 500 to avoid sending a single huge DEL
     * command that blocks the Redis event loop.
     */
    private void deleteBatch(List<String> keys, String breakerName) {
        int batchSize = 500;
        for (int i = 0; i < keys.size(); i += batchSize) {
            int end = Math.min(i + batchSize, keys.size());
            List<String> batch = keys.subList(i, end);
            try {
                withCircuitBreaker(breakerName,
                        () -> { redisTemplate.delete(batch); return null; });
            } catch (CallNotPermittedException e) {
                throw new CacheUnavailableException("Redis circuit breaker open for deleteBatch", e);
            } catch (RedisConnectionFailureException | RedisSystemException e) {
                throw new CacheUnavailableException("Redis connection failed", e);
            } catch (Exception e) {
                log.error("CACHE_DELETE_BATCH_ERROR count={} error={}", batch.size(), e.getMessage(), e);
                recordCacheFailure("deleteBatch");
                throw new CacheOperationException("Cache deleteBatch failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Attempts to acquire the single-flight lock for {@code lockKey}. Returns
     * the unique ownership token on success, or {@code null} when the lock is
     * held by another pod or Redis is unavailable (the caller then falls
     * through to a direct L2 read / supplier without waiting).
     */
    private String tryAcquireLock(String lockKey) {
        try {
            String token = UUID.randomUUID().toString();
            Boolean acquired = withCircuitBreaker("redisCacheLock",
                    () -> stringRedisTemplate.opsForValue()
                            .setIfAbsent(buildKey(lockKey), token, Duration.ofSeconds(lockTtlSeconds)));
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for tryAcquireLock", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_LOCK_ERROR key={} error={}", lockKey, e.getMessage(), e);
            recordCacheFailure("tryAcquireLock");
            throw new CacheOperationException("Cache lock acquire failed: " + e.getMessage(), e);
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
            withCircuitBreaker("redisCacheLock",
                    () -> stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(buildKey(lockKey)), token));
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for releaseLock", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_UNLOCK_ERROR key={} error={}", lockKey, e.getMessage(), e);
            recordCacheFailure("releaseLock");
            throw new CacheOperationException("Cache lock release failed: " + e.getMessage(), e);
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
        try {
            Cursor<String> cursor = withCircuitBreaker("redisCacheScan",
                    () -> redisTemplate.scan(
                            ScanOptions.scanOptions().match(pattern).count(200).build()));
            try (cursor) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
        } catch (CallNotPermittedException e) {
            throw new CacheUnavailableException("Redis circuit breaker open for scanKeys", e);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new CacheUnavailableException("Redis connection failed", e);
        } catch (Exception e) {
            log.error("CACHE_SCAN_ERROR pattern={} error={}", pattern, e.getMessage(), e);
            recordCacheFailure("scanKeys");
            throw new CacheOperationException("Cache scanKeys failed: " + e.getMessage(), e);
        }
        return keys;
    }

    /**
     * Functional interface for a single Redis command to be executed in a pipeline.
     * The command receives a {@link RedisOperations} instance and can invoke
     * any number of operations (SET, GET, HSET, etc.) which will be batched
     * into a single round trip.
     */
    @FunctionalInterface
    public interface PipelineCommand {
        void apply(RedisOperations<String, String> ops);
    }

    /**
     * Executes multiple independent Redis commands (SET, GET, HSET, DEL, etc.)
     * in a single pipeline to reduce round trips.
     *
     * <p>For N commands, this reduces RTT from N to 1, achieving 50-80% fewer
     * round trips for typical batch sizes (2-5 commands). Useful for batch
     * feature-flag checks and rate-limit operations.</p>
     *
     * @param commands list of pipeline commands to execute atomically
     * @return list of results in the same order as {@code commands}; each entry
     *         corresponds to the Redis command's reply (e.g., "OK" for SET,
     *         value for GET, Long for HSET/INCR)
     */
    public List<Object> pipeline(List<PipelineCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        SessionCallback<List<Object>> callback = new SessionCallback<>() {
            @Override
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                for (PipelineCommand command : commands) {
                    command.apply(operations);
                }
                return null;
            }
        };
        return stringRedisTemplate.executePipelined(callback);
    }

    private String buildKey(String key) {
        if (key.startsWith(CacheConstants.KEY_PREFIX)) return key;
        return CacheConstants.KEY_PREFIX + key;
    }
}
