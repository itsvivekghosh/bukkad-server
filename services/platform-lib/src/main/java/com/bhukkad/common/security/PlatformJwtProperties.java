package com.bhukkad.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

/**
 * Per-service JWT validation configuration (platform-lib).
 *
 * @param secret            shared HMAC secret; during the RS256 cutover it is
 *                          also the LEGACY verification path (HS256 grace
 *                          window, ADR-004) — disable with
 *                          {@code hmac-grace-enabled=false} once all issued
 *                          tokens are RS256
 * @param jwksUrl           identity's JWKS endpoint (RS256 verification path)
 * @param issuer            expected {@code iss} claim (validated when set)
 * @param audience          expected {@code aud} claim (validated when set)
 * @param hmacGraceEnabled  when true (default) HS256 tokens signed with the
 *                          shared secret are still accepted alongside RS256
 *                          JWKS verification (ADR-004 step 2: validators
 *                          prefer JWKS keys and fall back to the HMAC secret
 *                          during the grace window). Flip to false in a later
 *                          release to retire HS256 entirely.
 */
@ConfigurationProperties(prefix = "app.auth.jwt")
public record PlatformJwtProperties(
    String secret,
    String jwksUrl,
    String issuer,
    String audience,
    Boolean hmacGraceEnabled
) {
    /** Grace defaults ON (the cutover window is the default state now). */
    public boolean hmacGrace() {
        return hmacGraceEnabled == null || hmacGraceEnabled;
    }

    /** Backwards-compatible constructor (HMAC grace enabled). */
    public PlatformJwtProperties(String secret, String jwksUrl, String issuer, String audience) {
        this(secret, jwksUrl, issuer, audience, null);
    }

    public boolean enabled() {
        return (secret != null && !secret.isBlank()) || (jwksUrl != null && !jwksUrl.isBlank());
    }

    /**
     * Whether this profile must treat platform-JWT configuration mistakes as
     * boot failures instead of runtime rejections (audit V-15/G-3 matrix,
     * aligned with {@code SecretValidationConfig}'s prod/staging scope).
     */
    public boolean requiredInProfile(String profile) {
        return "prod".equalsIgnoreCase(profile) || "staging".equalsIgnoreCase(profile);
    }

    /** {@link #requiredInProfile(String)} against the running environment. */
    public boolean requiredInProfile(Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String active : environment.getActiveProfiles()) {
            if (requiredInProfile(active)) {
                return true;
            }
        }
        return false;
    }
}
