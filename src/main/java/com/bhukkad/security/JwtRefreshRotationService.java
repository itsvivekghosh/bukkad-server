package com.bhukkad.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed refresh-token rotation: revoking a token's {@code jti} claim
 * permanently invalidates it for the remainder of the refresh window, so a
 * reused/leaked refresh token is rejected even though its signature and
 * expiry claim are still valid.
 *
 * <p><b>Availability tradeoff:</b> Redis failures are deliberately fail-open
 * so that legitimate users are never locked out during a Redis outage. A
 * {@link #revoke(String)} that cannot reach Redis logs a warning and the
 * token is treated as NOT revoked by {@link #isRevoked(String)}. This means
 * a brief Redis outage can allow a rotated token to be reused, but it can
 * never cause a denial of service against valid sessions.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtRefreshRotationService {

    private static final String REVOKED_PREFIX = "bhukkad:revoked-jti:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.jwt.refresh-expiration:604800000}")
    private long refreshExpirationMs;

    /**
     * Marks the token identified by {@code jti} as revoked for the rest of
     * the refresh-token lifetime. Fail-open on Redis errors (see class doc).
     */
    public void revoke(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    REVOKED_PREFIX + jti, "1", refreshExpirationMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            log.warn("REFRESH_ROTATION | revoke failed, failing open | jti={} | error={}",
                    jti, ex.getMessage());
        }
    }

    /**
     * Returns {@code true} if the token identified by {@code jti} has been
     * revoked. Fail-open on Redis errors: an unreachable store is treated as
     * "not revoked" so users are not locked out during a Redis outage.
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(REVOKED_PREFIX + jti));
        } catch (RuntimeException ex) {
            log.warn("REFRESH_ROTATION | revoked check failed, failing open | jti={} | error={}",
                    jti, ex.getMessage());
            return false;
        }
    }

    /**
     * Rotates a refresh token: revokes the old token's {@code jti} so it can
     * no longer be used, and returns nothing — the caller issues the new token.
     */
    public void rotate(String oldJti) {
        revoke(oldJti);
    }
}