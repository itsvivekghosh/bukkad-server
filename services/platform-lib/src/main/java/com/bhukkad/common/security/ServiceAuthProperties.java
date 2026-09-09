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
     * Empty/blank means NO service may authenticate (deny-by-default).
     */
    private String allowedServices = "";

    /**
     * When true (default) requests to internal path prefixes must carry a
     * valid service token, even if another security rule (e.g. permitAll)
     * would otherwise allow them through.
     */
    private boolean enforceInternalPaths = true;

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

    public boolean isEnforceInternalPaths() {
        return enforceInternalPaths;
    }

    public void setEnforceInternalPaths(boolean enforceInternalPaths) {
        this.enforceInternalPaths = enforceInternalPaths;
    }
}
