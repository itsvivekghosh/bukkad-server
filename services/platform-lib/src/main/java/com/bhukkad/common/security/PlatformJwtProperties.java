package com.bhukkad.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

/**
 * Per-service JWT validation configuration (platform-lib).
 */
@ConfigurationProperties(prefix = "app.auth.jwt")
public class PlatformJwtProperties {

    private String secret;
    private String jwksUrl;
    private String issuer;
    private String audience;
    private Boolean hmacGraceEnabled;

    public PlatformJwtProperties() {
    }

    public PlatformJwtProperties(String secret, String jwksUrl, String issuer, String audience, Boolean hmacGraceEnabled) {
        this.secret = secret;
        this.jwksUrl = jwksUrl;
        this.issuer = issuer;
        this.audience = audience;
        this.hmacGraceEnabled = hmacGraceEnabled;
    }

    public PlatformJwtProperties(String secret, String jwksUrl, String issuer, String audience) {
        this(secret, jwksUrl, issuer, audience, null);
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Boolean getHmacGraceEnabled() {
        return hmacGraceEnabled;
    }

    public void setHmacGraceEnabled(Boolean hmacGraceEnabled) {
        this.hmacGraceEnabled = hmacGraceEnabled;
    }

    // Record-style accessors for test compatibility
    public String secret() {
        return secret;
    }

    public String jwksUrl() {
        return jwksUrl;
    }

    public String issuer() {
        return issuer;
    }

    public String audience() {
        return audience;
    }

    public Boolean hmacGraceEnabled() {
        return hmacGraceEnabled;
    }

    /** Grace defaults ON (the cutover window is the default state now). */
    public boolean hmacGrace() {
        return hmacGraceEnabled == null || hmacGraceEnabled;
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
