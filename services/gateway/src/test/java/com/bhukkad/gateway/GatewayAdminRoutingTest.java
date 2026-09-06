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
import java.nio.charset.StandardCharsets;

/**
 * Edge-routing E2E for the admin-analytics catch-all (migration batch B1).
 *
 * <p>The churn/experiment/feature-flag endpoints were ported from the monolith
 * into admin-analytics; this proves the gateway's {@code /api/v1/admin/**}
 * catch-all selects the admin-analytics backend for each of the three new
 * surfaces (and does NOT leak more specific admin slices owned by referral
 * and order). A live loopback JDK backend asserts 200 + marker body, mirroring
 * {@link GatewayRoutingTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.show-details=always",
                "management.endpoints.web.exposure.include=health"
        })
@AutoConfigureWebTestClient
class GatewayAdminRoutingTest {

    private static HttpServer adminBackend;
    private static String adminBase;
    private static HttpServer restaurantBackend;
    private static String restaurantBase;

    @BeforeAll
    static void startBackends() throws Exception {
        adminBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        adminBackend.createContext("/", ex -> respond(ex, "ADMIN-ANALYTICS-BACKEND"));
        adminBackend.start();
        adminBase = "http://127.0.0.1:" + adminBackend.getAddress().getPort();

        restaurantBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        restaurantBackend.createContext("/", ex -> respond(ex, "RESTAURANT-BACKEND"));
        restaurantBackend.start();
        restaurantBase = "http://127.0.0.1:" + restaurantBackend.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String marker) throws java.io.IOException {
        byte[] body = marker.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain");
        ex.sendResponseHeaders(200, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @AfterAll
    static void stopBackends() {
        if (adminBackend != null) {
            adminBackend.stop(0);
        }
        if (restaurantBackend != null) {
            restaurantBackend.stop(0);
        }
    }

    @DynamicPropertySource
    static void routeProperties(DynamicPropertyRegistry registry) {
        // The admin-analytics + restaurant backends are live (the latter to
        // prove the /api/v1/admin/restaurants/** carve-out); every other
        // route points at a refused port so a wrong selection fails loudly
        // instead of passing.
        registry.add("app.routes.admin-analytics-uri", () -> adminBase);
        registry.add("app.routes.restaurant-uri", () -> restaurantBase);
        registry.add("app.routes.identity-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.order-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.payment-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.delivery-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.notification-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.search-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.survey-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.referral-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.support-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.realtime-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.growth-uri", () -> "http://127.0.0.1:1");
        registry.add("app.routes.personalization-uri", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private WebTestClient client;

    @Test
    void churnHighRiskRouteHitsAdminAnalyticsBackend() {
        client.get().uri("/api/v1/admin/churn/high-risk").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ADMIN-ANALYTICS-BACKEND");
    }

    @Test
    void churnRescoreRouteHitsAdminAnalyticsBackend() {
        client.post().uri("/api/v1/admin/churn/rescore/42").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ADMIN-ANALYTICS-BACKEND");
    }

    @Test
    void experimentExposuresRouteHitsAdminAnalyticsBackend() {
        client.get().uri("/api/v1/admin/experiments/checkout-cta-copy/exposures").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ADMIN-ANALYTICS-BACKEND");
    }

    @Test
    void featureFlagRoutesHitAdminAnalyticsBackend() {
        client.get().uri("/api/v1/admin/feature-flags/checkout-v2").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ADMIN-ANALYTICS-BACKEND");
        client.get().uri("/api/v1/admin/feature-flags").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ADMIN-ANALYTICS-BACKEND");
    }

    @Test
    void adminRestaurantsAndCommissionAreCarvedOutToRestaurantService() {
        // The old premise "legacy admin paths still hit admin-analytics" died
        // with the route-table refactor: /api/v1/admin/restaurants/** and
        // /api/v1/commission/** are restaurant-domain surfaces and must be
        // selected by the narrower admin routes declared before the admin
        // catch-all. (This assertion failed against the committed table at
        // d2393ce; corrected here rather than left red.)
        client.get().uri("/api/v1/admin/restaurants/stats").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("RESTAURANT-BACKEND");
        client.get().uri("/api/v1/commission/payouts").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("RESTAURANT-BACKEND");
    }

    @Test
    void genericAdminPathsStillHitAdminAnalyticsCatchAll() {
        client.get().uri("/api/v1/admin/audit-log").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ADMIN-ANALYTICS-BACKEND");
    }
}
