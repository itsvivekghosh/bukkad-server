package com.bhukkad.common.web;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Adds standard security headers to every response.
 *
 * <p>These headers were previously enforced by the monolith's standalone nginx
 * ingress and {@code SecurityHeadersFilter}. In the microservices world each
 * service must set them itself (or rely on the gateway, but defense-in-depth
 * prefers both).</p>
 */
@Component
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse http = (HttpServletResponse) response;
        http.setHeader("X-Frame-Options", "DENY");
        http.setHeader("X-Content-Type-Options", "nosniff");
        http.setHeader("X-XSS-Protection", "1; mode=block");
        http.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        http.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        http.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        chain.doFilter(request, response);
    }
}
