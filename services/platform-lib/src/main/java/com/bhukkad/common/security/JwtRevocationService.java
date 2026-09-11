package com.bhukkad.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Logout / credential-change token-revocation epoch (P1): a Redis key
 * {@code revocation:token-epoch:<userId>} holds the {@link Instant} of the
 * latest logout, password change or account deactivation. Validators reject
 * any access token whose {@code iat} predates that epoch, closing the
 * "access tokens live up to 15 min after logout" window — without a
 * per-token denylist.
 *
 * <p>Degradation contract:
 * <ul>
 *   <li><b>Redis not configured</b> (no {@link StringRedisTemplate} bean —
 *       minimal test contexts): the check is skipped entirely, writes are
 *       no-ops. Revocation is an availability-neutral hardening layer, so
 *       its absence never blocks a boot.</li>
 *   <li><b>Redis unreachable</b>: fail-OPEN on reads (tokens keep
 *       authenticating — same posture as the rate limiter V-18) and the
 *       bypass is observable via the {@link #METRIC_BYPASS_REDIS_ERROR}
 *       counter. Writes never throw: a logout must not fail because Redis
 *       is down (the refresh-token row revocation still happened).</li>
 * </ul>
 *
 * <p>The epoch key TTL ({@code app.auth.jwt.revocation-epoch-ttl-minutes},
 * default 15) MUST be at least the issuer's access-token TTL: after it the
 * newest pre-epoch token has expired anyway, so letting the key lapse loses
 * nothing.</p>
 */
@Component
public class JwtRevocationService {

    private static final Logger log = LoggerFactory.getLogger(JwtRevocationService.class);

    /** Redis key prefix — one epoch per principal. */
    public static final String EPOCH_KEY_PREFIX = "revocation:token-epoch:";

    /** Observable bypass counter (fail-open on a Redis outage). */
    public static final String METRIC_BYPASS_REDIS_ERROR = "jwt_revocation_check_bypass";

    private final StringRedisTemplate redisTemplate; // nullable: Redis not configured
    private final MeterRegistry meterRegistry;       // nullable: contexts without actuator
    private final Duration epochTtl;

    @Autowired
    public JwtRevocationService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                ObjectProvider<MeterRegistry> meterRegistryProvider,
                                @Value("${app.auth.jwt.revocation-epoch-ttl-minutes:15}") long epochTtlMinutes) {
        this(redisTemplateProvider.getIfAvailable(), meterRegistryProvider.getIfAvailable(),
                Duration.ofMinutes(epochTtlMinutes));
    }

    /** Test constructor: bypassing the Spring ObjectProvider. */
    JwtRevocationService(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry, Duration epochTtl) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.epochTtl = epochTtl;
    }

    /** True when a Redis template is wired (check active); false = skip entirely. */
    public boolean isConfigured() {
        return redisTemplate != null;
    }

    /**
     * The principal's revocation epoch, or empty when none is stored, Redis
     * is not configured, or Redis is unreachable (fail-open, counted).
     */
    public Optional<Instant> revocationEpoch(long userId) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            String stored = redisTemplate.opsForValue().get(EPOCH_KEY_PREFIX + userId);
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(Instant.parse(stored));
        } catch (RedisConnectionFailureException | RedisSystemException | DateTimeParseException
                 | IllegalStateException e) {
            // RedisConnectionFailureException + RedisSystemException: the Redis-outage
            // class (same mapping as RedisRateLimitService). IllegalStateException wraps
            // a lazy Lettuce "connection closed" surface on some code paths.
            return bypassedByRedisError(userId, e);
        }
    }

    /**
     * Advances the principal's revocation epoch to now(): every access token
     * minted BEFORE this call becomes invalid after the ±2 s clock-slack
     * grace. No-op when Redis is not configured; Redis failures are logged
     * and swallowed (fail-open for the validator, never breaks the caller's
     * transaction).
     */
    public void revokeTokensIssuedBefore(long userId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(EPOCH_KEY_PREFIX + userId,
                    Instant.now().toString(), epochTtl);
            log.info("TOKEN_REVOCATION_EPOCH_SET userId={} ttlMinutes={}", userId, epochTtl.toMinutes());
        } catch (RedisConnectionFailureException | RedisSystemException | IllegalStateException e) {
            log.warn("TOKEN_REVOCATION_EPOCH_WRITE_FAILED userId={} — pre-epoch tokens stay valid "
                    + "until their access TTL: {}", userId, e.getMessage());
        }
    }

    private Optional<Instant> bypassedByRedisError(long userId, Exception cause) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_BYPASS_REDIS_ERROR).increment();
        }
        // DEBUG (not WARN): a Redis outage turns this into a hot-path log
        // storm; the counter is the outage signal.
        log.debug("Token-revocation epoch check bypassed (Redis error) userId={}: {}", userId, cause.getMessage());
        return Optional.empty();
    }
}
