package com.bhukkad.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the {@link SecurityHeadersFilter}. The filter is enabled
 * by default and adds a base set of security hardening headers, plus any
 * extra headers supplied via {@code app.security-headers.custom-headers}.
 */
@Data
@ConfigurationProperties(prefix = "app.security-headers")
public class SecurityHeadersProperties {

    /** Master switch for the security-headers filter. */
    private boolean enabled = true;

    /** Extra response headers injected on top of the base set. */
    private Map<String, String> customHeaders = new LinkedHashMap<>();
}
