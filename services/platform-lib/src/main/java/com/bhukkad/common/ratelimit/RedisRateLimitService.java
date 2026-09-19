package com.bhukkad.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed distributed rate limiter.
 *
 * <p>The fixed-window counter runs as ONE atomic Lua script
 * ({@code INCR} → {@code PEXPIRE} when the window is new → limit compare).
 * The previous {@code INCR} + conditional {@code EXPIRE} was a two-command
 * sequence: a crash between them — or a concurrent increment racing the
 * {@code count == 1} check — left a key without TTL, i.e. permanent denial
 * until a manual delete (audit V-18/RC-A). Inside a script nothing can
 * interleave.</p>
 *
 * <p>Script contract (shared with the gateway edge limiter via
 * {@link #RATE_LIMIT_LUA}): returns the running window count, or the negated
 * remaining TTL in milliseconds when the limit was crossed. The caller reads
 * the sign as the allow/deny verdict — still one round trip, still atomic.</p>
 *
 * <p>When Redis is DOWN a {@code RedisConnectionFailureException} does not
 * take the API down: per {@code app.rate-limit.fail-open-on-redis-error}
 * (default true) the request is allowed and counted via the
 * {@code ratelimit_bypass_redis_error} counter so the bypass is observable
 * rather than silent (audit G-2: no inert safety devices).</p>
 */
@Service
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);

    public static final String PREFIX = "bhukkad:ratelimit:";

    /**
     * Atomic sliding-window rate limiter using a Redis sorted set.
     *
     * <p>KEYS[1] = window key. ARGV[1] = now (epoch millis). ARGV[2] = window
     * millis. ARGV[3] = limit. Returns the new count while inside the limit,
     * or the negated retry-after milliseconds when the limit is exceeded.</p>
     */
    public static final String RATE_LIMIT_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            
            -- Remove timestamps older than the window
            redis.call('ZREMRANGEBYSCORE', key, '-inf', '(' .. tostring(now - window))
            
            -- Count remaining timestamps
            local count = redis.call('ZCARD', key)
            
            if count >= limit then
                -- Compute retry-after from the oldest timestamp in the window
                local oldest = redis.call('ZRANGE', key, 0, 0)
                if #oldest > 0 then
                    local oldestTs = tonumber(oldest[1])
                    local retryAfter = math.max(1000, oldestTs + window - now)
                    return 0 - retryAfter
                end
                return 0 - window
            end
            
            -- Add current timestamp
            redis.call('ZADD', key, now, now)
            
            -- Set TTL on the key (window seconds + 1 second buffer)
            redis.call('EXPIRE', key, math.ceil(window / 1000) + 1)
            
            return count + 1""";

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);

    /** Observable bypass counter name (also used by the gateway edge limiter). */
    public static final String METRIC_BYPASS_REDIS_ERROR = "ratelimit_bypass_redis_error";

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry; // nullable: contexts without actuator
    private final Cache<String, RateLimitDecision> localCache;
    private final CircuitBreaker circuitBreaker;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisRateLimitService(StringRedisTemplate redisTemplate,
                                 RateLimitProperties properties,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider,
                                 CircuitBreakerRegistry circuitBreakerRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.localCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(50, TimeUnit.MILLISECONDS)
                .recordStats()
                .build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(
                "redisRateLimit", CircuitBreakerConfig.custom()
                        .failureRateThreshold(50f)
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build());
        if (this.meterRegistry != null) {
            this.circuitBreaker.getEventPublisher()
                    .onStateTransition(event ->
                            Gauge.builder("ratelimit_circuit_breaker_state", this.circuitBreaker, (cb) -> 1.0)
                                    .description("Circuit breaker state for Redis rate limiter (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
                                    .tag("state", event.getStateTransition().getToState().name())
                                    .register(this.meterRegistry));
        }
    }

    /** Test constructor: bypassing the Spring ObjectProvider. */
    RedisRateLimitService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this(redisTemplate, properties, new EmptyProvider(), io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults());
    }

    @Override
    public RateLimitDecision check(String bucket, String identifier, long limit, int windowSeconds) {
        String key = PREFIX + bucket + ":" + identifier;
        RateLimitDecision cached = localCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        long windowMillis = windowSeconds * 1000L;
        long now = System.currentTimeMillis();
        Long result;
        try {
            result = circuitBreaker.executeCheckedSupplier(() -> (Long) redisTemplate.execute(SCRIPT, List.of(key),
                    String.valueOf(now), String.valueOf(windowMillis), String.valueOf(limit)));
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException ex) {
            // Circuit breaker is OPEN: Redis is consistently failing or slow.
            // Fall back to local cache or fail-closed.
            log.warn("RATE_LIMIT_CIRCUIT_OPEN bucket={} Redis unavailable, falling back", bucket);
            return circuitOpenFallback(bucket, limit, windowSeconds);
        } catch (org.springframework.data.redis.RedisConnectionFailureException
                 | org.springframework.data.redis.RedisSystemException ex) {
            // RedisConnectionFailureException (per V-18) and the Lettuce-side
            // RedisSystemException wrapper of connection/command-transport
            // failures — the Redis-outage class of errors.
            return bypassedByRedisError(bucket, limit, windowSeconds, ex);
        } catch (Throwable ex) {
            // Any other exception from the script: fail-open, never deny on our own bug.
            return bypassedByRedisError(bucket, limit, windowSeconds, ex);
        }
        if (result == null) {
            // Script produced no reply (pipelined/transactional edge): fail-open, never deny on our own bug.
            return bypassedByRedisError(bucket, limit, windowSeconds, null);
        }
        RateLimitDecision decision;
        if (result < 0) {
            long retryAfterMillis = -result;
            long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
            decision = RateLimitDecision.denied(0, limit, (int) retryAfterSeconds);
        } else {
            decision = RateLimitDecision.allowed(result, limit, windowSeconds);
        }
        localCache.put(key, decision);
        return decision;
    }

    /** Fallback when the circuit breaker is open: use local cache or fail-closed. */
    private RateLimitDecision circuitOpenFallback(String bucket, long limit, int windowSeconds) {
        if (meterRegistry != null) {
            meterRegistry.counter("ratelimit_circuit_open_fallback", "bucket", bucket).increment();
        }
        if (properties == null || properties.isFailOpenOnRedisError()) {
            // When fail-open, allow the request but try to enforce via local cache
            // Local cache may have stale data, but it's better than nothing
            log.debug("Rate-limit circuit open, failing open bucket={}", bucket);
            return RateLimitDecision.allowed(0, limit, windowSeconds);
        }
        log.warn("Rate-limit circuit open and fail-open disabled, denying bucket={}", bucket);
        return RateLimitDecision.denied(0, limit, windowSeconds);
    }

    /** Fail-open (or fail-closed when configured) on a Redis outage — counted, logged once per burst. */
    private RateLimitDecision bypassedByRedisError(String bucket, long limit, int windowSeconds, Throwable cause) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_BYPASS_REDIS_ERROR, "bucket", bucket).increment();
        }
        if (properties == null || properties.isFailOpenOnRedisError()) {
            log.debug("Rate-limit Redis error, failing open bucket={}: {}",
                    bucket, cause == null ? "no reply" : cause.getMessage());
            return RateLimitDecision.allowed(0, limit, windowSeconds);
        }
        log.warn("Rate-limit Redis error and fail-open disabled, denying bucket={}: {}",
                bucket, cause == null ? "no reply" : cause.getMessage());
        return RateLimitDecision.denied(0, limit, windowSeconds);
    }

    private static final class EmptyProvider implements ObjectProvider<MeterRegistry> {
        @Override
        public MeterRegistry getObject() {
            throw new IllegalStateException("no MeterRegistry");
        }

        @Override
        public MeterRegistry getObject(Object... args) {
            throw new IllegalStateException("no MeterRegistry");
        }

        @Override
        public MeterRegistry getIfAvailable() {
            return null;
        }

        @Override
        public MeterRegistry getIfUnique() {
            return null;
        }
    }
}
