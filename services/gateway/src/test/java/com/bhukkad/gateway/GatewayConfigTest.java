package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, no-network test of the strangler route table.
 *
 * <p>Asserts the gateway wires the intended routes (restaurant slice, identity
 * auth, order slice, payment slice, delivery slice) with the configured backend
 * URIs, and (audit batch A) the edge-hardening defaults: bounded httpclient
 * timeouts plus the header-hygiene / secure-cookie global filters.</p>
 */
@SpringBootTest(classes = {GatewayApplication.class})
@TestPropertySource(properties = {
        "app.routes.restaurant-uri=http://bhukkad-restaurant.bhukkad.svc.cluster.local",
        "app.routes.identity-uri=http://bhukkad-identity.bhukkad.svc.cluster.local",
        "app.routes.order-uri=http://bhukkad-order.bhukkad.svc.cluster.local",
        "app.routes.payment-uri=http://bhukkad-payment.bhukkad.svc.cluster.local",
        "app.routes.delivery-uri=http://bhukkad-delivery.bhukkad.svc.cluster.local",
        "app.routes.search-uri=http://bhukkad-search.bhukkad.svc.cluster.local",
        "app.routes.survey-uri=http://bhukkad-survey.bhukkad.svc.cluster.local",
        "app.routes.referral-uri=http://bhukkad-referral.bhukkad.svc.cluster.local",
        "app.routes.support-uri=http://bhukkad-support.bhukkad.svc.cluster.local",
        "app.routes.notification-uri=http://bhukkad-notification.bhukkad.svc.cluster.local",
        "app.routes.admin-analytics-uri=http://bhukkad-admin-analytics.bhukkad.svc.cluster.local",
        "app.routes.realtime-uri=http://bhukkad-realtime.bhukkad.svc.cluster.local",
        "app.routes.growth-uri=http://bhukkad-growth.bhukkad.svc.cluster.local",
        "app.routes.personalization-uri=http://bhukkad-personalization.bhukkad.svc.cluster.local"
})
class GatewayConfigTest {

    @Autowired
    private RouteLocator routes;

    @Autowired
    private HttpClientProperties httpClientProperties;

    @Autowired
    private java.util.List<GlobalFilter> globalFilters;

    @Test
    void stranglerRouteTableIsDefined() {
        Map<String, Route> byId = routes.getRoutes()
                .collect(Collectors.toMap(Route::getId, r -> r))
                .block();

        assertThat(byId).containsKeys(
                "notification", "survey",
                "customer-cart", "customer-wallet", "customer-group-orders",
                "customer-subscriptions", "customer-order-extras",
                "customer-loyalty-self", "customer-support-self",
                "customer-recommendations", "customer-surprise-me",
                "customer-orders", "cart-legacy",
                "home-feed", "home-campaigns", "home-membership",
                "cache", "platform", "compliance",
                "analytics-exports", "swagger",
                "search", "referral", "support",
                "notification", "live", "live-realtime", "growth",
                "inventory", "restaurant", "identity", "personalization",
                "order", "payment", "delivery",
                "admin-restaurant-stats", "admin-restaurants", "commission",
                "admin-analytics", "unmatched", "not-found");
        // Count covers the route table as restored with the concurrent-session
        // overlay (analytics CSV exports + swagger aggregate + the explicit
        // admin-restaurants/stats carve-out kept the analytics read model) plus
        // the observable unmatched-/api 404 route (audit V-20).
        assertThat(routes.getRoutes().collectList().block()).hasSize(53);

        Route restaurant = byId.get("restaurant");
        assertThat(restaurant.getUri().getScheme()).isEqualTo("http");
        assertThat(restaurant.getUri().getHost())
                .isEqualTo("bhukkad-restaurant.bhukkad.svc.cluster.local");

        Route identity = byId.get("identity");
        assertThat(identity.getUri().getHost())
                .isEqualTo("bhukkad-identity.bhukkad.svc.cluster.local");

        Route order = byId.get("order");
        assertThat(order.getUri().getHost())
                .isEqualTo("bhukkad-order.bhukkad.svc.cluster.local");

        Route payment = byId.get("payment");
        assertThat(payment.getUri().getHost())
                .isEqualTo("bhukkad-payment.bhukkad.svc.cluster.local");

        Route delivery = byId.get("delivery");
        assertThat(delivery.getUri().getHost())
                .isEqualTo("bhukkad-delivery.bhukkad.svc.cluster.local");

        Route notification = byId.get("notification");
        assertThat(notification.getUri().getHost())
                .isEqualTo("bhukkad-notification.bhukkad.svc.cluster.local");

        Route adminAnalytics = byId.get("admin-analytics");
        assertThat(adminAnalytics.getUri().getHost())
                .isEqualTo("bhukkad-admin-analytics.bhukkad.svc.cluster.local");
    }

    @Test
    void edgeHardeningDefaultsAreConfigured() {
        // Audit batch A: bounded upstream latency, no hung-service pinning.
        assertThat(httpClientProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(httpClientProperties.getConnectTimeout()).isEqualTo(5000);

        // Header hygiene + secure-cookie rewriting are global filters and run
        // on every route (the programmatic RouteLocator is invisible to
        // spring.cloud.gateway.default-filters).
        assertThat(globalFilters)
                .anyMatch(TransportHeaderHygieneFilter.class::isInstance)
                .anyMatch(SecureCookieFilter.class::isInstance);
        // Audit V-18/PERF-1.3: edge Redis bucket limiter mounted as a global
        // filter; audit G-2 makes it greppable in the wired chain.
        assertThat(globalFilters)
                .anyMatch(EdgeRateLimitFilter.class::isInstance);
    }

    @Test
    void unmatchedPathNormalization_boundsMetricCardinality() {
        // Numeric and UUID segments collapse to {id}, everything else verbatim
        // (audit V-20 normalized-path counter).
        assertThat(GatewayConfig.normalizePath("/api/v1/customers/42/cart"))
                .isEqualTo("/api/v1/customers/{id}/cart");
        assertThat(GatewayConfig.normalizePath(
                "/api/v1/orders/0f5f4a7c-7e0f-4b0b-8f4c-c5f0f7a55c0d/disputes"))
                .isEqualTo("/api/v1/orders/{id}/disputes");
        assertThat(GatewayConfig.normalizePath("/api/v1/inventory-system"))
                .isEqualTo("/api/v1/inventory-system");
        assertThat(GatewayConfig.normalizePath("/"))
                .isEqualTo("/");
    }
}
