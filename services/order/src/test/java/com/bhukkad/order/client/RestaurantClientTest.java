package com.bhukkad.order.client;

import com.bhukkad.order.client.dto.RestaurantResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the order → restaurant service-to-service client (plan §P2):
 * GET /api/v1/restaurants/public/{id} returns the restaurant profile, and
 * failures degrade to an empty Mono (no cascade).
 */
class RestaurantClientTest {

    private HttpServer server;
    private int port;
    private RestaurantClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        port = server.getAddress().getPort();
        client = new RestaurantClient("http://localhost:" + port);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getRestaurant_returnsProfile_whenServiceResponds() {
        server.createContext("/api/v1/restaurants/public/42", exchange -> {
            String body = "{\"id\":42,\"name\":\"Biryani House\",\"cuisineType\":\"INDIAN\","
                    + "\"averageRating\":4.5,\"isActive\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        RestaurantResponse response = client.getRestaurant(42L).block();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getName()).isEqualTo("Biryani House");
        assertThat(response.getCuisineType()).isEqualTo("INDIAN");
        assertThat(response.getAverageRating()).isEqualTo(4.5);
        assertThat(response.getIsActive()).isTrue();
    }

    @Test
    void getRestaurant_returnsEmpty_whenServiceReturns404() {
        server.createContext("/api/v1/restaurants/public/99", exchange ->
                exchange.sendResponseHeaders(404, -1));

        RestaurantResponse response = client.getRestaurant(99L).block();

        assertThat(response).isNull();
    }

    @Test
    void getRestaurant_returnsEmpty_whenServiceUnreachable() {
        // No handler registered for this path → connection still succeeds but
        // returns 404; use a closed port to simulate an unreachable service.
        RestaurantClient unreachableClient = new RestaurantClient("http://localhost:1");

        RestaurantResponse response = unreachableClient.getRestaurant(1L).block();

        assertThat(response).isNull();
    }

    // ---- menu item error contract (cart/order money path) ----

    @Test
    void getMenuItem_returnsPayload_on200() {
        server.createContext("/api/v1/menu/items/7", exchange -> {
            String body = "{\"id\":7,\"name\":\"Paneer Tikka\",\"price\":250.00,\"restaurantId\":3}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var item = client.getMenuItem(7L).block();

        assertThat(item).isNotNull();
        assertThat(item.get("name")).isEqualTo("Paneer Tikka");
        // restaurantId extraction helper (coupon scoping + legacy order create).
        assertThat(client.getMenuItemRestaurantId(7L).block()).isEqualTo(3L);
    }

    @Test
    void getMenuItem_returnsEmpty_whenServiceReturns404() {
        // Genuine 404 → EMPTY ("item does not exist"), never a false-positive
        // "unavailable": callers map empty to a truthful 404.
        server.createContext("/api/v1/menu/items/99", exchange ->
                exchange.sendResponseHeaders(404, -1));

        var item = client.getMenuItem(99L).block();

        assertThat(item).isNull();
    }

    @Test
    void getMenuItem_propagatesNon404Errors_soCallersSurface503() {
        // 5xx must NOT collapse to empty — that would report a live item as
        // missing during upstream churn; callers map it to 503 instead.
        server.createContext("/api/v1/menu/items/5", exchange ->
                exchange.sendResponseHeaders(500, -1));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> client.getMenuItem(5L).block())
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class)
                .hasMessageContaining("500");
    }

    @Test
    void searchRestaurants_returnsList_whenServiceResponds() {
        // Mirrors the restaurant service's real contract post-extraction:
        // GET /api/v1/restaurants?name= returns a bare RestaurantSummary[]
        // (fields "active"/"avgRating"), which the client maps via aliases.
        server.createContext("/api/v1/restaurants", exchange -> {
            String body = "[{\"id\":1,\"name\":\"Pizza Place\",\"description\":\"Wood-fired pizza\","
                    + "\"cuisineId\":3,\"address\":\"12 MG Road\",\"phone\":\"9999900001\","
                    + "\"active\":true,\"avgRating\":4.2},"
                    + "{\"id\":2,\"name\":\"Taco Stand\",\"description\":\"Street tacos\","
                    + "\"cuisineId\":7,\"address\":\"45 Residency Road\",\"phone\":\"9999900002\","
                    + "\"active\":true,\"avgRating\":4.0}]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var results = client.searchRestaurants("pizza").block();

        assertThat(results).isNotNull().hasSize(2);
        assertThat(results.get(0).getName()).isEqualTo("Pizza Place");
        assertThat(results.get(1).getName()).isEqualTo("Taco Stand");
        assertThat(results.get(0).getIsActive()).isTrue();
        assertThat(results.get(0).getAverageRating()).isEqualTo(4.2);
    }

    // ---- stock reservation surface (order saga RESERVE_STOCK step) ----

    private java.util.List<com.bhukkad.order.client.dto.StockReservationLine> reservationLines() {
        return java.util.List.of(
                com.bhukkad.order.client.dto.StockReservationLine.of(100L, "Paneer", 1),
                com.bhukkad.order.client.dto.StockReservationLine.of(101L, "Roti", 2));
    }

    @Test
    void reserveStock_sendsLinesAndToken_returnsReservedLines_on200() {
        final String[] capturedToken = new String[1];
        final String[] capturedBody = new String[1];
        server.createContext("/api/v1/inventory/stock-reservation/reserve", exchange -> {
            capturedToken[0] = exchange.getRequestHeaders().getFirst("X-Service-Token");
            capturedBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = ("[" + capturedBody[0].substring(1, capturedBody[0].length() - 1) + "]")
                    .getBytes(StandardCharsets.UTF_8); // echo the same items back
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var reserved = client.reserveStock(reservationLines(), "svc-token").block();

        assertThat(reserved).isEqualTo(reservationLines());
        assertThat(capturedToken[0]).isEqualTo("svc-token");
        assertThat(capturedBody[0]).contains("\"menuItemId\":100").contains("\"quantity\":2");
    }

    @Test
    void reserveStock_insufficientStock409_failsTheMono_soSagaStepCanFail() {
        server.createContext("/api/v1/inventory/stock-reservation/reserve", exchange ->
                exchange.sendResponseHeaders(409, -1));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> client.reserveStock(reservationLines(), "svc-token").block())
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);
    }

    @Test
    void releaseStock_omitsHeaderWhenTokenNull_succeedsOn200EmptyBody() {
        server.createContext("/api/v1/inventory/stock-reservation/release", exchange -> {
            assertThat(exchange.getRequestHeaders().containsKey("X-Service-Token")).isFalse();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        var released = client.releaseStock(reservationLines(), null).block();

        assertThat(released).isEqualTo(reservationLines());
    }
}
