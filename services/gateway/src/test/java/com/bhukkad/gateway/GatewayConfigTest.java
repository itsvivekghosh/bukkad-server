package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

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
class GatewayConfigTest {

    @Autowired
    private RouteLocator routes;

    @Test
    void stranglerRouteTableIsDefined() {
        Map<String, Route> byId = routes.getRoutes()
                .collect(Collectors.toMap(Route::getId, r -> r))
                .block();

        assertThat(byId).containsKeys(
                "restaurant", "identity", "order", "payment", "delivery");
        assertThat(routes.getRoutes().collectList().block()).hasSize(5);

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
    }
}
