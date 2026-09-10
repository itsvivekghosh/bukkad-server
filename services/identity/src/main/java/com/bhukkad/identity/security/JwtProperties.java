package com.bhukkad.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * JWT issuer configuration (plan §8: identity is the authN source of
 * truth; every other service validates the tokens it issues).
 *
 * @param secret           HMAC signing secret (min 32 bytes for HS256). Kept
 *                         for the ADR-004 grace window: legacy HS256 tokens
 *                         remain verifiable until the fleet-wide cutover
 *                         completes and the property is retired
 * @param ttlMinutes       legacy token horizon (minutes); kept ONLY for
 *                         backward compatibility with internal callers and
 *                         as the fallback for {@code accessTtlMinutes} when
 *                         the dedicated access-token TTL key is absent
 * @param accessTtlMinutes bearer access-token validity window (minutes).
 *                         15 by default: stolen access tokens expire quickly;
 *                         renewal goes through rotating refresh tokens.
 * @param refreshTtlDays   absolute refresh-token session horizon in days
 *                         (30 default); rotation never extends past it
 * @param audience         audience claim stamped into issued tokens
 *                         ({@code bhukkad-api} default)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long ttlMinutes, long accessTtlMinutes, long refreshTtlDays,
                            String audience) {

    public static final long DEFAULT_REFRESH_TTL_DAYS = 30;
    public static final String DEFAULT_AUDIENCE = "bhukkad-api";

    @ConstructorBinding
    public JwtProperties(String secret, long ttlMinutes, long accessTtlMinutes, long refreshTtlDays,
                         String audience) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("app.jwt.secret must be at least 32 chars");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.ttl-minutes must be positive");
        }
        if (accessTtlMinutes <= 0) {
            accessTtlMinutes = ttlMinutes;
        }
        if (refreshTtlDays <= 0) {
            refreshTtlDays = DEFAULT_REFRESH_TTL_DAYS;
        }
        this.secret = secret;
        this.ttlMinutes = ttlMinutes;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
        this.audience = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience;
    }

    /** Backwards-compatible constructor (access TTL follows {@code ttl-minutes}). */
    public JwtProperties(String secret, long ttlMinutes) {
        this(secret, ttlMinutes, ttlMinutes, DEFAULT_REFRESH_TTL_DAYS, null);
    }

    /** Backwards-compatible constructor (refresh horizon falls to the default). */
    public JwtProperties(String secret, long ttlMinutes, long accessTtlMinutes) {
        this(secret, ttlMinutes, accessTtlMinutes, DEFAULT_REFRESH_TTL_DAYS, null);
    }
}
