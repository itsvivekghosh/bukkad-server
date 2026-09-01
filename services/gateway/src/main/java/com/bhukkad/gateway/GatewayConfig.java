package com.bhukkad.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strangler route table (P0 + P4 first slice).
 *
 * <p>Routes are path-driven so cutting over a domain is a predicate edit only:
 * the restaurant slice is served by the {@code restaurant} service, everything
 * else falls through to the monolith ({@code bhukkad-app}) as the safe default.
 * Backend URIs are k8s Service DNS names — no service-discovery stack needed.</p>
 *
 * <p>Cutover order follows the ownership matrix (§3): identity (auth) →
 * restaurant (read slice) → order → payment → delivery → notification →
 * admin-analytics → monolith teardown.</p>
 */
@Configuration
public class GatewayConfig {

    private final String restaurantUri;
    private final String identityUri;
    private final String orderUri;
    private final String monolithUri;

    public GatewayConfig(@Value("${app.routes.restaurant-uri}") String restaurantUri,
                         @Value("${app.routes.identity-uri}") String identityUri,
                         @Value("${app.routes.order-uri}") String orderUri,
                         @Value("${app.routes.monolith-uri}") String monolithUri) {
        this.restaurantUri = restaurantUri;
        this.identityUri = identityUri;
        this.orderUri = orderUri;
        this.monolithUri = monolithUri;
    }

    /**
     * Defines the path → backend routing table.
     *
     * @return the {@link RouteLocator} consulted by the gateway on every request
     */
    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                // Strangler slice: restaurant read surface (owned by the
                // restaurant service per the ownership matrix).
                .route("restaurant", r -> r.path(
                        "/restaurants/**",
                        "/menu/**",
                        "/cuisines/**",
                        "/search/**",
                        "/reviews/**",
                        "/feed/**").uri(restaurantUri))
                // Identity cut-over (P3): auth endpoints served by the identity
                // service; everything else on /api/** still falls to the monolith.
                // Declared before "monolith" so /api/v1/auth/** wins the match.
                .route("identity", r -> r.path(
                        "/api/v1/auth/**").uri(identityUri))
                // Order-service strangler slice (P5): order/cart endpoints.
                // Order is gated on restaurant + payment + delivery extraction.
                .route("order", r -> r.path(
                        "/api/v1/orders/**",
                        "/api/v1/cart/**").uri(orderUri))
                // Auth + remaining API surface currently owned by the monolith.
                .route("monolith", r -> r.path(
                        "/api/**",
                        "/auth/**").uri(monolithUri))
                // Safe catch-all: anything unmatched still reaches the monolith
                // so flipping routes one-by-one can never 404 a live path.
                .route("monolith-fallback", r -> r.path("/**").uri(monolithUri))
                .build();
    }
}
