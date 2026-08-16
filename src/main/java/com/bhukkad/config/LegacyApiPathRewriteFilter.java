package com.bhukkad.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rewrites legacy {@code /api/...} paths to {@code /api/v1/...} for backward compatibility.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyApiPathRewriteFilter extends OncePerRequestFilter {

    private static final String LEGACY_PREFIX = "/api/";
    private static final String VERSIONED_PREFIX = ApiPaths.V1_PREFIX + "/";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();
        if (shouldRewrite(uri)) {
            String rewritten = ApiPaths.V1_PREFIX + uri.substring("/api".length());
            filterChain.doFilter(new RewrittenUriRequest(request, rewritten), response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean shouldRewrite(String uri) {
        if (!uri.startsWith(LEGACY_PREFIX)) {
            return false;
        }
        return !uri.startsWith(ApiPaths.V1_PREFIX + "/") && !uri.equals(ApiPaths.V1_PREFIX);
    }

    private static final class RewrittenUriRequest extends HttpServletRequestWrapper {

        private final String rewrittenUri;

        private RewrittenUriRequest(HttpServletRequest request, String rewrittenUri) {
            super(request);
            this.rewrittenUri = rewrittenUri;
        }

        @Override
        public String getRequestURI() {
            return rewrittenUri;
        }

        @Override
        public String getServletPath() {
            return rewrittenUri;
        }
    }
}
