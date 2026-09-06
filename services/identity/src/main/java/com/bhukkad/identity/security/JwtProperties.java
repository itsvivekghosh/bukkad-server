package com.bhukkad.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * JWT issuer configuration (plan §8: identity is the authN source of
 * truth; every other service validates the tokens it issues).
 *
 * @param secret           HMAC signing secret (min 32 bytes for HS256)
 * @param ttlMinutes       legacy token horizon (minutes); also the fallback
 *                         for {@code accessTtlMinutes} when the dedicated
 *                         access-token TTL key is absent
 * @param accessTtlMinutes bearer access-token validity window (minutes).
 *                         15 by default: stolen access tokens expire quickly;
 *                         renewal goes through rotating refresh tokens.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long ttlMinutes, long accessTtlMinutes) {

    @ConstructorBinding
    public JwtProperties(String secret, long ttlMinutes, long accessTtlMinutes) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("app.jwt.secret must be at least 32 chars");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.ttl-minutes must be positive");
        }
        if (accessTtlMinutes <= 0) {
            accessTtlMinutes = ttlMinutes;
        }
        this.secret = secret;
        this.ttlMinutes = ttlMinutes;
        this.accessTtlMinutes = accessTtlMinutes;
    }

    /** Backwards-compatible constructor (access TTL follows {@code ttl-minutes}). */
    public JwtProperties(String secret, long ttlMinutes) {
        this(secret, ttlMinutes, ttlMinutes);
    }
}
