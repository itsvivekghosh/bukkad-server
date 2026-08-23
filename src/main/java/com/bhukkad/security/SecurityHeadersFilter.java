package com.bhukkad.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Injects security hardening response headers (CSP, HSTS, X-Frame-Options,
 * etc.) on every response. Runs at the front of the security filter chain so
 * the headers are present even when a downstream filter rejects the request.
 *
 * <p>The base set is defined in {@link #buildDefaultHeaders()}; additional
 * headers can be supplied via {@code app.security-headers.custom-headers}
 * (see {@link SecurityHeadersProperties}).</p>
 */
@Component
@Order(0)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final Map<String, String> DEFAULT_HEADERS = buildDefaultHeaders();

    private final SecurityHeadersProperties properties;

    public SecurityHeadersFilter(SecurityHeadersProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (properties.isEnabled()) {
            DEFAULT_HEADERS.forEach(response::setHeader);
            properties.getCustomHeaders().forEach(response::setHeader);
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/webjars")
                || path.startsWith("/favicon.ico");
    }

    private static Map<String, String> buildDefaultHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Security-Policy", "default-src 'self'");
        headers.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Frame-Options", "DENY");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        return headers;
    }
}