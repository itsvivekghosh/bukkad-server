package com.bhukkad.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for service-to-service authentication.
 */
@ConfigurationProperties(prefix = "app.auth.service")
public class ServiceAuthProperties {
    /**
     * Shared secret for service-to-service JWT tokens.
     * When set, internal service calls must include a valid JWT signed with this secret.
     */
    private String jwtSecret = "";

    /**
     * Allowed service IDs that can make internal calls.
     * Comma-separated list of service names (e.g., "order,payment,delivery").
     */
    private String allowedServices = "";

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getAllowedServices() {
        return allowedServices;
    }

    public void setAllowedServices(String allowedServices) {
        this.allowedServices = allowedServices;
    }
}
