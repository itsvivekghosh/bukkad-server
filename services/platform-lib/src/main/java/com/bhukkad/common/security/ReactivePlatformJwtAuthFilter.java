package com.bhukkad.common.security;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Reactive counterpart of {@link PlatformJwtAuthFilter}: populates the Spring
 * Security context from the platform JWT in a WebFlux pipeline.
 *
 * <p>The filter is inert while {@link PlatformJwtValidator#isEnabled()} is
 * false (no secret/JWKS configured): the surrounding
 * {@code authenticated()} rule answers 401 — fail-closed.</p>
 */
public class ReactivePlatformJwtAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ReactivePlatformJwtAuthFilter.class);

    public static final String MDC_USER_ID = "userId";
    public static final String MDC_USER_EMAIL = "userEmail";
    public static final String MDC_USER_ROLE = "userRole";

    private final PlatformJwtValidator validator;
    private final ServerAuthenticationEntryPoint entryPoint;

    public ReactivePlatformJwtAuthFilter(PlatformJwtValidator validator,
                                         ServerAuthenticationEntryPoint entryPoint) {
        this.validator = validator;
        this.entryPoint = entryPoint;
    }

    @Override
    @Nonnull
    public Mono<Void> filter(@Nonnull ServerWebExchange exchange, @Nonnull WebFilterChain chain) {
        if (!validator.isEnabled()) {
            return chain.filter(exchange);
        }
        String token = bearerToken(exchange.getRequest());
        if (token == null) {
            return chain.filter(exchange);
        }
        return Mono.fromCallable(() -> validator.validate(token))
                .flatMap(optional -> optional.map(principal -> authenticate(exchange, chain, principal))
                        .orElseGet(() -> {
                            clearMdc();
                            if (entryPoint != null) {
                                return entryPoint.commence(exchange,
                                        new org.springframework.security.core.AuthenticationException("JWT invalid") {});
                            }
                            return chain.filter(exchange);
                        }))
                .onErrorResume(e -> {
                    clearMdc();
                    if (entryPoint != null) {
                        return entryPoint.commence(exchange,
                                new org.springframework.security.core.AuthenticationException("JWT invalid", e) {});
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain, TokenPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.scope() != null
                ? List.of(new SimpleGrantedAuthority("ROLE_" + principal.scope().toUpperCase(Locale.ROOT)))
                : List.of();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .doOnSuccess(v -> setMdc(principal))
                .doOnError(t -> clearMdc());
    }

    private String bearerToken(org.springframework.http.server.reactive.ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void setMdc(TokenPrincipal principal) {
        MDC.put(MDC_USER_ID, String.valueOf(principal.userId()));
        MDC.put(MDC_USER_EMAIL, principal.email());
        if (principal.scope() != null) {
            MDC.put(MDC_USER_ROLE, "ROLE_" + principal.scope().toUpperCase(Locale.ROOT));
        }
        log.debug("Authenticated principal userId={} scope={}", principal.userId(), principal.scope());
    }

    private void clearMdc() {
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_USER_EMAIL);
        MDC.remove(MDC_USER_ROLE);
    }
}
