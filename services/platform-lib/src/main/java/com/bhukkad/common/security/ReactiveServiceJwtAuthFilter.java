package com.bhukkad.common.security;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Locale;

/**
 * Reactive counterpart of {@link ServiceJwtAuthFilter}: validates service-to-service
 * JWT tokens in the {@code X-Service-Token} header for WebFlux pipelines.
 *
 * <p>Internal paths require a valid service token; other paths authenticate
 * the caller as a service principal when a valid token is present.</p>
 */
public class ReactiveServiceJwtAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ReactiveServiceJwtAuthFilter.class);
    public static final String ROLE_SERVICE = "ROLE_SERVICE";
    static final String HEADER = "X-Service-Token";

    /** rejection reason tags (audit V-15 observability contract). */
    public static final String REASON_ABSENT = "absent";
    public static final String REASON_INVALID = "invalid";
    public static final String REASON_FORBIDDEN = "forbidden";
    public static final String REASON_WEAK_KEY = "weakkey";

    private static final List<String> DEFAULT_INTERNAL_PATTERNS =
            List.of("/api/v1/internal/**", "/internal/**");
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final ServiceAuthProperties properties;
    private final List<String> internalPatterns;
    private final boolean enforceInternal;
    private final ServerAuthenticationEntryPoint entryPoint;

    public ReactiveServiceJwtAuthFilter(ServiceAuthProperties properties,
                                        ServerAuthenticationEntryPoint entryPoint) {
        this(properties, DEFAULT_INTERNAL_PATTERNS, true, entryPoint);
    }

    ReactiveServiceJwtAuthFilter(ServiceAuthProperties properties, List<String> internalPatterns,
                                boolean enforceInternal, ServerAuthenticationEntryPoint entryPoint) {
        this.properties = properties;
        this.internalPatterns = internalPatterns;
        this.enforceInternal = enforceInternal;
        this.entryPoint = entryPoint;
    }

    @Override
    @Nonnull
    public Mono<Void> filter(@Nonnull ServerWebExchange exchange, @Nonnull WebFilterChain chain) {
        if (StringUtils.hasText(properties.getJwtSecret())) {
            // secret configured: verify whenever a header is presented
            return validateToken(exchange, chain);
        }
        // No secret configured: run ONLY to fail-closed on enforced internal paths
        if (enforceInternal && properties.isEnforceInternalPaths() && isInternalPath(exchange.getRequest())) {
            return reject(exchange, REASON_ABSENT, "Service token required");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> validateToken(ServerWebExchange exchange, WebFilterChain chain) {
        String serviceToken = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (!StringUtils.hasText(serviceToken)) {
            if (enforceInternal && properties.isEnforceInternalPaths() && isInternalPath(exchange.getRequest())) {
                return reject(exchange, REASON_ABSENT, "Service token required");
            }
            return chain.filter(exchange);
        }

        return Mono.fromCallable(() -> {
            try {
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var claims = io.jsonwebtoken.Jwts.parser()
                        .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(serviceToken)
                        .getPayload();
                return claims;
            } catch (io.jsonwebtoken.security.WeakKeyException e) {
                throw new WeakKeyException(e);
            } catch (Exception e) {
                throw new InvalidTokenException(e);
            }
        })
        .flatMap(claims -> {
            String serviceId = claims.getSubject();
            if (serviceId == null || !isAllowedService(serviceId)) {
                log.warn("Rejected service token from disallowed subject={}", serviceId);
                return reject(exchange, REASON_FORBIDDEN, "Service not allowed");
            }
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    serviceId, null, List.of(new SimpleGrantedAuthority(ROLE_SERVICE)));
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        })
        .onErrorResume(WeakKeyException.class, e -> reject(exchange, REASON_WEAK_KEY, "Service token cannot be verified"))
        .onErrorResume(InvalidTokenException.class, e -> reject(exchange, REASON_INVALID, "Invalid service token"));
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason, String message) {
        log.warn("Service auth rejected reason={} path={}", reason, exchange.getRequest().getPath());
        if (entryPoint != null) {
            return entryPoint.commence(exchange, new org.springframework.security.core.AuthenticationException(message) {});
        }
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private boolean isInternalPath(org.springframework.http.server.reactive.ServerHttpRequest request) {
        String path = request.getPath().value();
        return internalPatterns.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private boolean isAllowedService(String serviceId) {
        if (!StringUtils.hasText(properties.getAllowedServices())) {
            return false;
        }
        return List.of(properties.getAllowedServices().split(","))
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(s -> s.equalsIgnoreCase(serviceId));
    }

    private static class WeakKeyException extends RuntimeException {
        WeakKeyException(Throwable cause) { super(cause); }
    }

    private static class InvalidTokenException extends RuntimeException {
        InvalidTokenException(Throwable cause) { super(cause); }
    }
}
