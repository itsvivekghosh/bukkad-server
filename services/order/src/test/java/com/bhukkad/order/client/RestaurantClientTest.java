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

    @Test
    void searchRestaurants_returnsList_whenServiceResponds() {
        server.createContext("/api/v1/restaurants/public/search", exchange -> {
            String body = "[{\"id\":1,\"name\":\"Pizza Place\",\"cuisineType\":\"ITALIAN\","
                    + "\"averageRating\":4.2,\"isActive\":true},"
                    + "{\"id\":2,\"name\":\"Taco Stand\",\"cuisineType\":\"MEXICAN\","
                    + "\"averageRating\":4.0,\"isActive\":true}]";
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
    }
}
