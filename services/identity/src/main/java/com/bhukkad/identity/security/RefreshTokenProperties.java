package com.bhukkad.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh-token session policy ({@code app.refresh-token.*}). Tokens are
 * 256-bit URL-safe random values persisted only as SHA-256 hashes; the TTL
 * is the ABSOLUTE session horizon — rotation never extends it.
 *
 * @param ttlMinutes refresh-token lifetime in minutes (negative/zero falls
 *                   back to 24h, matching the pre-rotation JWT window)
 */
@ConfigurationProperties(prefix = "app.refresh-token")
public record RefreshTokenProperties(long ttlMinutes) {

    public static final long DEFAULT_TTL_MINUTES = 1440;

    public RefreshTokenProperties {
        if (ttlMinutes <= 0) {
            ttlMinutes = DEFAULT_TTL_MINUTES;
        }
    }
}
