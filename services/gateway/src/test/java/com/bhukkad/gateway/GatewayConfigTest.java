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
 * <p>Asserts the gateway wires exactly the three intended routes (restaurant
 * slice, monolith API/auth, monolith catch-all) with the configured backend URIs
 * — i.e. a strangler flip is always safe (unmatched paths still hit the monolith).</p>
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

        assertThat(byId).containsKeys("restaurant", "identity", "order", "monolith", "monolith-fallback");
        assertThat(routes.getRoutes().collectList().block()).hasSize(5);

        Route restaurant = byId.get("restaurant");
        // URI is normalized with the default http port (:80); compare host/scheme
        // to stay robust against port normalization.
        assertThat(restaurant.getUri().getScheme()).isEqualTo("http");
        assertThat(restaurant.getUri().getHost())
                .isEqualTo("bhukkad-restaurant.bhukkad.svc.cluster.local");
        assertThat(restaurant.getPredicate()).isNotNull();

        Route identity = byId.get("identity");
        assertThat(identity.getUri().getHost())
                .isEqualTo("bhukkad-identity.bhukkad.svc.cluster.local");

        Route order = byId.get("order");
        assertThat(order.getUri().getHost())
                .isEqualTo("bhukkad-order.bhukkad.svc.cluster.local");

        Route monolith = byId.get("monolith");
        assertThat(monolith.getUri().getHost())
                .isEqualTo("bhukkad-app.bhukkad.svc.cluster.local");

        Route fallback = byId.get("monolith-fallback");
        assertThat(fallback.getUri().getHost())
                .isEqualTo("bhukkad-app.bhukkad.svc.cluster.local");
    }
}
