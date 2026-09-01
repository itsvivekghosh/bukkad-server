package com.bhukkad.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;

/**
 * End-to-end routing proof for the strangler gateway (P0).
 *
 * <p>Spins a JDK {@link HttpServer} on an ephemeral loopback port to stand in
 * for the {@code restaurant} service and points the {@code monolith-uri} at a
 * refused port. Then asserts path predicates select the correct backend:
 * restaurant-slice paths reach the backend (200 + marker body), monolith paths
 * reach the refused backend (503 — i.e. the route matched, not 404), and the
 * gateway's own health probe is UP. No Docker/WireMock required.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.show-details=always",
                "management.endpoints.web.exposure.include=health"
        })
@AutoConfigureWebTestClient
class GatewayRoutingTest {

    private static HttpServer restaurantBackend;
    private static String restaurantBase;

    private static HttpServer identityBackend;
    private static String identityBase;

    private static HttpServer orderBackend;
    private static String orderBase;

    @BeforeAll
    static void startBackends() throws Exception {
        restaurantBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        restaurantBackend.createContext("/", ex -> {
            byte[] body = "RESTAURANT-BACKEND".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        restaurantBackend.start();
        restaurantBase = "http://127.0.0.1:" + restaurantBackend.getAddress().getPort();

        identityBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identityBackend.createContext("/", ex -> {
            byte[] body = "IDENTITY-BACKEND".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        identityBackend.start();
        identityBase = "http://127.0.0.1:" + identityBackend.getAddress().getPort();

        orderBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        orderBackend.createContext("/", ex -> {
            byte[] body = "ORDER-BACKEND".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        orderBackend.start();
        orderBase = "http://127.0.0.1:" + orderBackend.getAddress().getPort();
    }

    @AfterAll
    static void stopBackends() {
        if (restaurantBackend != null) {
            restaurantBackend.stop(0);
        }
        if (identityBackend != null) {
            identityBackend.stop(0);
        }
        if (orderBackend != null) {
            orderBackend.stop(0);
        }
    }

    @DynamicPropertySource
    static void routeProperties(DynamicPropertyRegistry registry) {
        // Restaurant slice → live embedded backend.
        registry.add("app.routes.restaurant-uri", () -> restaurantBase);
        // Identity auth path → live embedded backend.
        registry.add("app.routes.identity-uri", () -> identityBase);
        // Order service path → live embedded backend.
        registry.add("app.routes.order-uri", () -> orderBase);
        // Monolith (fallback + everything else) → intentionally refused port.
        registry.add("app.routes.monolith-uri", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private WebTestClient client;

    @Test
    void restaurantSliceIsServedByRestaurantBackend() {
        client.get().uri("/restaurants/123").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("RESTAURANT-BACKEND");
    }

    @Test
    void cuisinesSliceIsServedByRestaurantBackend() {
        client.get().uri("/cuisines/all").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("RESTAURANT-BACKEND");
    }

    @Test
    void apiPathForUnmappedDomainFallsToMonolith() {
        // /api/v1/notifications (not mapped to any service) → monolith (refused → 503).
        client.get().uri("/api/v1/notifications").exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void authPathIsServedByIdentityBackend() {
        // /api/v1/auth/** must hit the identity service, not the monolith
        // fallback (which is refused) and not the restaurant backend.
        client.post().uri("/api/v1/auth/login")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("IDENTITY-BACKEND");
    }

    @Test
    void orderPathIsServedByOrderBackend() {
        // /api/v1/orders/** must hit the order service backend.
        client.get().uri("/api/v1/orders/123").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
    }

    @Test
    void cartPathIsServedByOrderBackend() {
        // /api/v1/cart/** must hit the order service backend.
        client.get().uri("/api/v1/cart/items").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
    }

    @Test
    void unmatchedPathHitsMonolithCatchAll() {
        // Anything not matched by a specific predicate still routes to the
        // monolith via the catch-all (never a 404) — safe strangler default.
        client.get().uri("/legacy/health-check").exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void gatewayHealthProbeIsUp() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                . jsonPath("$.status").isEqualTo("UP");
    }
}
