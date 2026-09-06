package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, no-network test of the strangler route table.
 *
 * <p>Asserts the gateway wires the intended routes (restaurant slice, identity
 * auth, order slice, payment slice, delivery slice) with the configured backend
 * URIs.</p>
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
                "search", "referral", "support",
                "notification", "live", "live-realtime", "growth",
                "inventory", "restaurant", "identity", "personalization",
                "order", "payment", "delivery",
                "admin-analytics", "not-found");
        assertThat(routes.getRoutes().collectList().block()).hasSize(34);

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
}
