package com.bhukkad.common.config;

/**
 * Central API path prefix. All REST controllers should use {@link #V1_PREFIX}.
 * Unversioned {@code /api/**} requests are rewritten to v1 by {@link com.bhukkad.common.web.LegacyApiPathRewriteFilter}.
 */
public final class ApiPaths {

    public static final String V1_PREFIX = "/api/v1";

    private ApiPaths() {
    }
}
