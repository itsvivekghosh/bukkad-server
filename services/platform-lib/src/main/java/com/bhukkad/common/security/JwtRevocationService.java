package com.bhukkad.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Redis-backed JWT revocation epochs (P1 REVOCATION, closes the
 * "logged-out user's access token lives to its 15-min TTL" gap).
 *
 * <p>One epoch per user: {@code jwt:revoked-before:<userId>} holds an
 * ISO-8601 {@link Instant} string. A validator rejects any access token whose
 * {@code iat} is strictly before the stored epoch — i.e. everything issued
 * before the user's last logout / credential change / deactivation. Tokens
 * issued AFTER the epoch (a fresh login) keep working.</p>
 *
 * <p>Key TTL semantics: the key only needs to outlive every token it
 * invalidates, so it MUST be configured at least as long as the platform's
 * maximum access-token TTL (default
 * {@code app.auth.jwt.revocation-key-ttl=15m} aligns with identity's
 * {@code app.jwt.access-ttl-minutes=15}; raise both together, never one).
 * Once the key expires, every still-alive token was necessarily issued after
 * the last revocation.</p>
 *
 * <p>Availability: writes are best-effort — a Redis failure degrades to the
 * pre-P1 behaviour (token dies with its TTL) instead of breaking logout.
 * Reads deliberately PROPAGATE failures: {@link PlatformJwtValidator} owns the
 * documented fail-open + {@code jwt_revocation_check_bypass} counter so the
 * bypass is observable at exactly one place. In contexts without a
 * {@link StringRedisTemplate} (unit/slice contexts) the service is inert:
 * writes no-op, reads report "no epoch", nothing blocks or fails.</p>
 */
@Component
public class JwtRevocationService {

    private static final Logger log = LoggerFactory.getLogger(JwtRevocationService.class);

    /** Redis key prefix; the full key is {@code jwt:revoked-before:<userId>}. */
    public static final String KEY_PREFIX = "jwt:revoked-before:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Duration keyTtl;

    @Autowired
    public JwtRevocationService(ObjectProvider<StringRedisTemplate> redisProvider,
                                @Value("${app.auth.jwt.revocation-key-ttl:15m}") Duration keyTtl) {
        this.redisProvider = redisProvider;
        // A zero/negative TTL would delete the key on write (or throw) — treat
        // it as unset so a misconfiguration degrades to the safe default.
        this.keyTtl = (keyTtl == null || keyTtl.isNegative() || keyTtl.isZero())
                ? Duration.ofMinutes(15) : keyTtl;
    }

    /** Non-Spring convenience (tests): 15-minute key TTL. */
    public JwtRevocationService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this(redisProvider, Duration.ofMinutes(15));
    }

    /**
     * Stamps {@code userId}'s revocation epoch to {@code epoch} (seconds
     * precision — matching JWT {@code iat} granularity). Monotonic: a call
     * with an EARLIER instant never shortens an existing epoch; each accepted
     * revocation restarts the key TTL so the epoch outlives every token it
     * kills. Best-effort by design: Redis errors are logged, never thrown —
     * a logout must not fail because the epoch store is down.
     */
    public void revoke(long userId, Instant epoch) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null || epoch == null) {
            return; // inert context (no Redis) — nothing to stamp
        }
        Instant candidate = epoch.truncatedTo(ChronoUnit.SECONDS);
        try {
            Instant existing = readEpoch(redis, userId);
            Instant effective = (existing == null || candidate.isAfter(existing)) ? candidate : existing;
            redis.opsForValue().set(KEY_PREFIX + userId, effective.toString(), keyTtl);
        } catch (RuntimeException e) {
            log.warn("JWT revocation write failed userId={} epoch={} (access token will expire "
                    + "with its TTL instead of being revoked early): {}", userId, candidate, e.toString());
        }
    }

    /**
     * Revoke everything the user currently holds: stamps the epoch at {@code now}
     * so every access token minted before this call dies at the next validation.
     * Call sites (logout, password change, admin deactivate) use this instead of
     * {@link #revoke(long, Instant)} directly. Never throws — an outage may not
     * fail the logout; tokens then expire on their own TTL.
     */
    public void revokeTokensIssuedBefore(long userId) {
        revoke(userId, Instant.now());
    }

    /**
     * The current revocation epoch for {@code userId}, or {@code null} when no
     * epoch is stored (never revoked / inert context). {@link RuntimeException}
     * propagates on Redis failure — callers decide the failure posture (see
     * class javadoc; the validator fail-opens with a bypass counter).
     */
    public Instant revokedBefore(long userId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        return readEpoch(redis, userId);
    }

    private Instant readEpoch(StringRedisTemplate redis, long userId) {
        String value = redis.opsForValue().get(KEY_PREFIX + userId);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            // Corrupt epoch values must never turn into an auth 500 storm;
            // behave like a Redis failure and let the caller's policy decide.
            throw new IllegalStateException("Unparsable JWT revocation epoch userId=" + userId, e);
        }
    }

    /** Whether this instance can actually store epochs (a Redis template exists). */
    public boolean isAvailable() {
        return redisProvider.getIfAvailable() != null;
    }
}
