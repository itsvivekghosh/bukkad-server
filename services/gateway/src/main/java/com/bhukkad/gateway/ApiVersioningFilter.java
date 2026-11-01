package com.bhukkad.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API versioning filter for production-safe version migration.
 *
 * <p>Supports two version selection strategies:</p>
 * <ol>
 *   <li><b>Path-based:</b> Requests to {@code /api/v2/**} are treated as API v2.</li>
 *   <li><b>Header-based:</b> {@code X-API-Version: 2} header selects API v2.</li>
 * </ol>
 *
 * <p>When no version is specified, the request defaults to v1 and receives
 * {@code Deprecation} and {@code Sunset} response headers so clients can
 * plan their migration to v2.</p>
 *
 * <p>Filter order: runs after routing ({@code Ordered.HIGHEST_PRECEDENCE + 50})
 * so the route has already been selected, but before the response is written.</p>
 */
@Component
public class ApiVersioningFilter implements GatewayFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiVersioningFilter.class);

    /** Header name for API version selection. */
    public static final String API_VERSION_HEADER = "X-API-Version";

    /** Current stable API version. */
    public static final String CURRENT_VERSION = "2";

    /** Deprecated version still in service. */
    public static final String DEPRECATED_VERSION = "1";

    /** Sunset date for v1 (RFC 8594 format: HTTP-date). */
    private static final String V1_SUNSET = "Sun, 31 Dec 2026 23:59:59 GMT";

    /** Link to versioning migration guide. */
    private static final String V1_LINK = "</api/docs/versioning>; rel=\"version-history\"";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        String requestedVersion = resolveVersion(exchange);

        if (DEPRECATED_VERSION.equals(requestedVersion) || requestedVersion == null) {
            // v1 or unspecified → add deprecation headers
            response.getHeaders().add("Deprecation", "true");
            response.getHeaders().add("Sunset", V1_SUNSET);
            response.getHeaders().add(HttpHeaders.LINK, V1_LINK);
            log.debug("API v1 request (deprecated): {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath());
        } else if (CURRENT_VERSION.equals(requestedVersion)) {
            log.debug("API v2 request: {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath());
        } else {
            // Unknown version → treat as v1 with deprecation
            response.getHeaders().add("Deprecation", "true");
            response.getHeaders().add("Sunset", V1_SUNSET);
            response.getHeaders().add(HttpHeaders.LINK, V1_LINK);
        }

        return chain.filter(exchange);
    }

    /**
     * Resolves the API version from path prefix or header.
     * Path takes precedence: {@code /api/v2/**} → "2".
     * Falls back to {@code X-API-Version} header.
     * Returns {@code null} when no version is specified (defaults to v1).
     */
    private String resolveVersion(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();

        // Path-based: /api/v2/** → version 2
        if (path.startsWith("/api/v2/") || path.equals("/api/v2")) {
            return CURRENT_VERSION;
        }
        if (path.startsWith("/api/v1/") || path.equals("/api/v1")) {
            return DEPRECATED_VERSION;
        }

        // Header-based: X-API-Version: 2
        String header = exchange.getRequest().getHeaders().getFirst(API_VERSION_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }

        // No version specified → default to v1 (deprecated)
        return null;
    }
}
