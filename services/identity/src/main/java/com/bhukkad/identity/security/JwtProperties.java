package com.bhukkad.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT issuer configuration (plan §8: identity is the authN source of
 * truth; every other service validates the tokens it issues).
 *
 * @param secret    HMAC signing secret (min 32 bytes for HS256)
 * @param ttlMinutes token validity window
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long ttlMinutes) {

    public JwtProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("app.jwt.secret must be at least 32 chars");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.ttl-minutes must be positive");
        }
    }
}
