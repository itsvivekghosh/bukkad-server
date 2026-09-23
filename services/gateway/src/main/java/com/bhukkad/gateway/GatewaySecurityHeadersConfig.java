package com.bhukkad.gateway;

import org.springframework.web.server.WebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds security headers to responses from the API Gateway.
 *
 * <p>Since the gateway is a WebFlux application without Spring Security,
 * headers are added via a WebFilter for defense-in-depth.</p>
 */
@Configuration
public class GatewaySecurityHeadersConfig {

    @Bean
    public WebFilter securityHeadersWebFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            var headers = exchange.getResponse().getHeaders();
            headers.add("X-Frame-Options", "DENY");
            headers.add("X-Content-Type-Options", "nosniff");
            headers.add("X-XSS-Protection", "1; mode=block");
            headers.add("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.add("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
            headers.add("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            headers.add("Content-Security-Policy",
                    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; "
                            + "connect-src 'self' ws: wss:; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                            + "script-src 'self'");
            return chain.filter(exchange);
        };
    }
}
