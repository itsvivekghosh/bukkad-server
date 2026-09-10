package com.bhukkad.identity.ratelimit;

import com.bhukkad.common.ratelimit.RateLimitExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Redis-backed failed-login lockout per (email, IP) (feature #5).
 *
 * <p>One atomic Lua script (same discipline as the platform
 * {@code RedisRateLimitService}) checks an active lock, else counts the
 * failure inside a sliding window and — once the threshold is crossed —
 * imposes an exponentially escalating lock (2^(strikes-1) × base, capped).
 * Everything lives behind a single EVAL, so INCR and the lock cannot race or
 * leave TTL-less keys behind.</p>
 *
 * <p>Script return contract (mirrors the platform limiter's sign convention):
 * a positive value is the running failure count (attempt allowed to
 * proceed to credential check), a negative value is the negated remaining
 * lock TTL in milliseconds (locked out).</p>
 *
 * <p>Redis outages fail OPEN (log + metric, never lock the world because the
 * counter is unavailable) — the same availability posture as the platform
 * limiter. A successful login clears counters, lock and escalation history.
 * Every deny and activation is observable via the {@code auth_lockout_active}
 * counter.</p>
 */
@Service
public class LoginLockoutService {

    private static final Logger log = LoggerFactory.getLogger(LoginLockoutService.class);

    public static final String METRIC_AUTH_LOCKOUT_ACTIVE = "auth_lockout_active";

    /** KEYS[1]=lock, KEYS[2]=failures, KEYS[3]=strikes. See class javadoc. */
    public static final String LOCKOUT_LUA = """
            local lockTtl = redis.call('PTTL', KEYS[1])
            if lockTtl and lockTtl > 0 then
              return 0 - lockTtl
            end
            local count = redis.call('INCR', KEYS[2])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[2], ARGV[1])
            end
            if count >= tonumber(ARGV[2]) then
              local strikes = redis.call('INCR', KEYS[3])
              if strikes == 1 then
                redis.call('PEXPIRE', KEYS[3], ARGV[5])
              end
              local lockMillis = tonumber(ARGV[3])
              local doublings = strikes - 1
              for i = 1, doublings do
                lockMillis = lockMillis * 2
              end
              if lockMillis > tonumber(ARGV[4]) then
                lockMillis = tonumber(ARGV[4])
              end
              redis.call('PSETEX', KEYS[1], lockMillis, '1')
              redis.call('DEL', KEYS[2])
              return 0 - lockMillis
            end
            return count""";

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(LOCKOUT_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginLockoutProperties properties;
    private final MeterRegistry meterRegistry; // nullable: contexts without actuator

    @org.springframework.beans.factory.annotation.Autowired
    public LoginLockoutService(StringRedisTemplate redisTemplate,
                               LoginLockoutProperties properties,
                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /** Test constructor without the Spring ObjectProvider. */
    LoginLockoutService(StringRedisTemplate redisTemplate, LoginLockoutProperties properties,
                        @Nullable MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Throws {@link RateLimitExceededException} (mapped to 429 + Retry-After)
     * while the (email, IP) pair is locked out.
     */
    public void assertAllowed(String email, String ip) {
        Long remainingMillis = lockedFor(key("lock", email, ip));
        if (remainingMillis != null && remainingMillis > 0) {
            long retryAfterSeconds = Math.max(1, (remainingMillis + 999) / 1000);
            record(METRIC_AUTH_LOCKOUT_ACTIVE, "deny");
            throw new RateLimitExceededException(
                    "Too many failed login attempts for this account; try again later",
                    retryAfterSeconds);
        }
    }

    /**
     * Records a failed credential attempt. When the threshold is crossed the
     * lock engages (exponentially escalating) and the method returns the lock
     * duration in milliseconds; otherwise the running failure count.
     */
    public long recordFailure(String email, String ip) {
        String lockKey = key("lock", email, ip);
        try {
            Long result = redisTemplate.execute(SCRIPT,
                    List.of(lockKey, key("fail", email, ip), key("strikes", email, ip)),
                    String.valueOf(properties.failureWindowSeconds() * 1000L),
                    String.valueOf(properties.threshold()),
                    String.valueOf(properties.baseLockSeconds() * 1000L),
                    String.valueOf(properties.maxLockSeconds() * 1000L),
                    String.valueOf(properties.strikesTtlSeconds() * 1000L));
            if (result == null) {
                return 0;
            }
            if (result < 0) {
                long lockMillis = -result;
                if (lockMillis >= (long) properties.baseLockSeconds() * 1000) {
                    record(METRIC_AUTH_LOCKOUT_ACTIVE, "activate");
                }
                return lockMillis;
            }
            return result;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.warn("Login-lockout Redis error; failing open for {}: {}",
                    identityOf(email, ip), e.getMessage());
            return 0;
        }
    }

    /** Successful credential check: clear counters, lock and escalation history. */
    public void recordSuccess(String email, String ip) {
        try {
            redisTemplate.delete(List.of(
                    key("lock", email, ip), key("fail", email, ip), key("strikes", email, ip)));
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.warn("Login-lockout Redis error on success path for {}: {}",
                    identityOf(email, ip), e.getMessage());
        }
    }

    /** Remaining lock TTL in millis, or null when not locked / Redis unavailable. */
    private Long lockedFor(String lockKey) {
        try {
            Long ttl = redisTemplate.getExpire(lockKey, java.util.concurrent.TimeUnit.MILLISECONDS);
            return (ttl == null || ttl < 0) ? null : ttl;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.warn("Login-lockout Redis error; failing open: {}", e.getMessage());
            return null;
        }
    }

    private void record(String metric, String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(metric, "outcome", outcome).increment();
        }
    }

    private static String key(String kind, String email, String ip) {
        return "bhukkad:auth:lockout:" + kind + ":" + identityOf(email, ip);
    }

    /** (email, IP) pair, email normalized so casing cannot split the counter. */
    private static String identityOf(String email, String ip) {
        String normalizedEmail = email == null ? "unknown" : email.trim().toLowerCase(Locale.ROOT);
        String normalizedIp = ip == null || ip.isBlank() ? "unknown" : ip;
        return normalizedEmail + "|" + normalizedIp.replace(':', '_');
    }

    /**
     * Caller IP for the lockout key. Remote address only —
     * {@code X-Forwarded-For} is client-spoofable (same posture as the
     * platform {@code WebRateLimitKeyResolver}).
     */
    public static String remoteIp(@Nullable HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = request.getRemoteAddr();
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }
}
