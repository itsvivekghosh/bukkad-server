package com.bhukkad.gateway;

import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.common.security.PlatformJwtValidator;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.common.security.authz.AuthorizationRequest;
import com.bhukkad.common.security.authz.AuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;
import java.util.Map;

/**
 * Feature-gated resource authorization skeleton at the edge.
 *
 * <p>The filter validates the platform JWT and passes the resulting
 * {@link TokenPrincipal} to the shared platform-lib
 * {@link AuthorizationService}. It is disabled by default, so existing gateway
 * traffic remains unchanged until both the flag and an applicable policy are
 * present. A missing or unknown policy fails closed when enabled.</p>
 *
 * <p>This is intentionally not an OPA client. The future OPA sidecar path is
 * to send this same {@link AuthorizationRequest} to a local OPA sidecar over
 * HTTP, evaluate a versioned Rego bundle there, and translate its boolean
 * decision back into the platform decision contract. The OPA dependency must
 * not be embedded in the gateway binary; keeping the policy engine behind
 * {@link AuthorizationService} makes that swap possible without changing
 * callers.</p>
 */
@Component
public class AuthorizationFilter implements GlobalFilter, Ordered {

    /** Before rate limiting and edge caching so cached responses cannot bypass authz. */
    static final int ORDER = -35;

    private final AuthorizationService authorizationService;
    private final PlatformJwtValidator jwtValidator;
    private final boolean enabled;
    private final String policyId;
    private final String resourceType;

    public AuthorizationFilter(AuthorizationService authorizationService,
                               PlatformJwtProperties jwtProperties,
                               @Value("${app.authz.enabled:false}") boolean enabled,
                               @Value("${app.authz.policy-id:gateway-default}") String policyId,
                               @Value("${app.authz.resource-type:http}") String resourceType) {
        this.authorizationService = authorizationService;
        this.jwtValidator = new PlatformJwtValidator(jwtProperties);
        this.enabled = enabled;
        this.policyId = policyId;
        this.resourceType = resourceType;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String token = bearerToken(exchange);
        if (token == null) {
            // Public routes remain reachable; protected upstream routes still
            // enforce authentication in their owning service.
            return chain.filter(exchange);
        }

        return Mono.fromCallable(() -> jwtValidator.validate(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optionalPrincipal -> optionalPrincipal
                        .map(principal -> authorize(exchange, chain, principal))
                        .orElseGet(() -> EdgeApiErrors.write(exchange,
                                org.springframework.http.HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED", "Invalid bearer token")));
    }

    private Mono<Void> authorize(ServerWebExchange exchange,
                                 GatewayFilterChain chain,
                                 TokenPrincipal principal) {
        String method = exchange.getRequest().getMethod() == null
                ? ""
                : exchange.getRequest().getMethod().name().toLowerCase(Locale.ROOT);
        String resourceId = exchange.getRequest().getURI().getPath();
        AuthorizationRequest request = AuthorizationRequest.fromPrincipal(
                principal, method, resourceType, resourceId, Map.of());
        var decision = authorizationService.check(policyId, request);
        if (!decision.allowed()) {
            return EdgeApiErrors.write(exchange,
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "FORBIDDEN", "Access denied");
        }
        return chain.filter(exchange);
    }

    private String bearerToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
