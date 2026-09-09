package com.bhukkad.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

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
     * Atomic increment + window-expiry + limit compare.
     *
     * <p>KEYS[1] = window key. ARGV[1] = window millis. ARGV[2] = limit.
     * Returns {@code count} while inside the limit, or {@code -TTL_ms} when
     * the limit is exceeded (self-healing a TTL-less leftover key by resetting
     * the window). Counted increments are never below 1, so a negative reply
     * is unambiguous.</p>
     */
    public static final String RATE_LIMIT_LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local limit = tonumber(ARGV[2])
            if c > limit then
              local t = redis.call('PTTL', KEYS[1])
              if t < 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                t = tonumber(ARGV[1])
              end
              return 0 - t
            end
            return c""";

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);

    /** Observable bypass counter name (also used by the gateway edge limiter). */
    public static final String METRIC_BYPASS_REDIS_ERROR = "ratelimit_bypass_redis_error";

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry; // nullable: contexts without actuator

    @org.springframework.beans.factory.annotation.Autowired
    public RedisRateLimitService(StringRedisTemplate redisTemplate,
                                 RateLimitProperties properties,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /** Test constructor: bypassing the Spring ObjectProvider. */
    RedisRateLimitService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this(redisTemplate, properties, new EmptyProvider());
    }

    @Override
    public RateLimitDecision check(String bucket, String identifier, long limit, int windowSeconds) {
        String key = PREFIX + bucket + ":" + identifier;
        long windowMillis = windowSeconds * 1000L;
        Long result;
        try {
            result = redisTemplate.execute(SCRIPT, List.of(key),
                    String.valueOf(windowMillis), String.valueOf(limit));
        } catch (org.springframework.data.redis.RedisConnectionFailureException
                 | org.springframework.data.redis.RedisSystemException ex) {
            // RedisConnectionFailureException (per V-18) and the Lettuce-side
            // RedisSystemException wrapper of connection/command-transport
            // failures — the Redis-outage class of errors.
            return bypassedByRedisError(bucket, limit, windowSeconds, ex);
        }
        if (result == null) {
            // Script produced no reply (pipelined/transactional edge): fail-open, never deny on our own bug.
            return bypassedByRedisError(bucket, limit, windowSeconds, null);
        }
        if (result < 0) {
            long retryAfterMillis = -result;
            long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
            return RateLimitDecision.denied(0, limit, retryAfterSeconds);
        }
        return RateLimitDecision.allowed(result, limit, windowSeconds);
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
