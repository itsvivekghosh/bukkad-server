package com.bhukkad.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * for each service backend and asserts path predicates select the correct
 * backend: restaurant-slice paths reach the backend (200 + marker body),
 * identity/auth paths reach identity, order/cart/coupon/dispute paths reach
 * order, notification paths reach notification, and the gateway's own health
 * probe is UP. No Docker/WireMock required.</p>
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

    private static HttpServer notificationBackend;
    private static String notificationBase;

    private static HttpServer realtimeBackend;
    private static String realtimeBase;

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

        notificationBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        notificationBackend.createContext("/", ex -> {
            byte[] body = "NOTIFICATION-BACKEND".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        notificationBackend.start();
        notificationBase = "http://127.0.0.1:" + notificationBackend.getAddress().getPort();

        realtimeBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        realtimeBackend.createContext("/", ex -> {
            byte[] body = "REALTIME-BACKEND".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        realtimeBackend.start();
        realtimeBase = "http://127.0.0.1:" + realtimeBackend.getAddress().getPort();
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
        if (notificationBackend != null) {
            notificationBackend.stop(0);
        }
        if (realtimeBackend != null) {
            realtimeBackend.stop(0);
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
        // Notification service path → live embedded backend.
        registry.add("app.routes.notification-uri", () -> notificationBase);
        // Payment service path → refused port (not under test).
        registry.add("app.routes.payment-uri", () -> "http://127.0.0.1:1");
        // Delivery service path → refused port (not under test).
        registry.add("app.routes.delivery-uri", () -> "http://127.0.0.1:1");
        // Admin-analytics service path → refused port (not under test).
        registry.add("app.routes.admin-analytics-uri", () -> "http://127.0.0.1:1");
        // Search service path → refused port (not under test).
        registry.add("app.routes.search-uri", () -> "http://127.0.0.1:1");
        // Survey service path → refused port (not under test).
        registry.add("app.routes.survey-uri", () -> "http://127.0.0.1:1");
        // Referral service path → refused port (not under test).
        registry.add("app.routes.referral-uri", () -> "http://127.0.0.1:1");
        // Support service path → refused port (not under test).
        registry.add("app.routes.support-uri", () -> "http://127.0.0.1:1");
        // Growth service path → refused port (not under test).
        registry.add("app.routes.growth-uri", () -> "http://127.0.0.1:1");
        // Realtime service path → live embedded backend (live SSE stream).
        registry.add("app.routes.realtime-uri", () -> realtimeBase);
        // Personalization service path → refused port (not under test).
        registry.add("app.routes.personalization-uri", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void headroomForParallelBuilds() {
        // @AutoConfigureWebTestClient's default 5s block-read timeout is too
        // tight when `mvn -T` boots several module JVMs at once — the gateway
        // context + 5 mock backends + routing chain can legitimately exceed
        // it. Mutate to 15s (far below surefire's hang budget).
        client = client.mutate().responseTimeout(java.time.Duration.ofSeconds(15)).build();
    }

    @Test
    void restaurantSliceIsServedByRestaurantBackend() {
        client.get().uri("/api/v1/restaurants/public/123").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("RESTAURANT-BACKEND");
    }

    @Test
    void cuisinesSliceIsServedByRestaurantBackend() {
        client.get().uri("/api/v1/cuisines/all").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("RESTAURANT-BACKEND");
    }

    @Test
    void notificationPathIsServedByNotificationBackend() {
        // /api/v1/notifications/** must hit the notification service backend.
        client.get().uri("/api/v1/notifications?recipient=user@test.com&channel=email")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("NOTIFICATION-BACKEND");
    }

    @Test
    void apiPathForUnmappedDomainReturns404() {
        // /api/v1/inventory-system (not mapped to any service) → 404 because
        // there is no fallback route to the decommissioned monolith.
        client.get().uri("/api/v1/inventory-system").exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void giftCardsPathIsServedByOrderBackend() {
        // /api/v1/gift-cards/** is routed to the order service.
        client.get().uri("/api/v1/gift-cards").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
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
        // /api/v1/customers/{id}/cart/** must hit the order service backend.
        client.get().uri("/api/v1/customers/1/cart/items").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
    }

    @Test
    void unmatchedPathReturns404() {
        // Anything not matched by a specific predicate returns 404 because
        // the monolith fallback has been removed (P8 teardown complete).
        client.get().uri("/legacy/health-check").exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void couponPathIsServedByOrderBackend() {
        // /api/v1/coupons/** must hit the order service backend (P2 extraction).
        client.get().uri("/api/v1/coupons/active").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
    }

    @Test
    void liveStreamPathIsServedByOrderBackend() {
        // /api/v1/orders/stream/** routes to the order service (order ownership
        // enforced server-side); /api/v1/live/** is the realtime SSE surface.
        client.get().uri("/api/v1/orders/stream/customer/123").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
    }

    @Test
    void liveRealtimePathIsServedByRealtimeBackend() {
        // /api/v1/live/** routes to the realtime service for SSE tracking.
        client.get().uri("/api/v1/live/tracking/123").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("REALTIME-BACKEND");
    }

    @Test
    void inventoryAlertPathIsServedByRestaurantBackend() {
        // /api/v1/inventory/alerts/** is narrower than /api/v1/restaurants/**
        // and must route to the restaurant service.
        client.get().uri("/api/v1/inventory/alerts/restaurants/1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("RESTAURANT-BACKEND");
    }

    @Test
    void customerOrderPathIsServedByOrderBackend() {
        // /api/v1/orders/** → order backend.
        client.get().uri("/api/v1/orders/1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
    }

    @Test
    void disputeAdminPathIsServedByOrderBackend() {
        // /api/v1/admin/disputes/** → order backend.
        client.get().uri("/api/v1/admin/disputes").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ORDER-BACKEND");
    }

    @Test
    void gatewayHealthProbeIsUp() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                . jsonPath("$.status").isEqualTo("UP");
    }
}
