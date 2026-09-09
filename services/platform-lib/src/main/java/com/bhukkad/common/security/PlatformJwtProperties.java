package com.bhukkad.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

@ConfigurationProperties(prefix = "app.auth.jwt")
public record PlatformJwtProperties(
    String secret,
    String jwksUrl,
    String issuer,
    String audience
) {
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