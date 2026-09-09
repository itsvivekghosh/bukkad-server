package com.bhukkad.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
}