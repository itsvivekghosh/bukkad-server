package com.bhukkad.search.infrastructure.client;

import com.bhukkad.search.infrastructure.client.SearchSourceClient.SourceMenu;
import com.bhukkad.search.infrastructure.client.SearchSourceClient.SourceMenuItem;
import com.bhukkad.search.infrastructure.client.SearchSourceClient.SourceRestaurant;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded source reader for the reconciliation sweep: every upstream failure
 * (dead host, 500, 404, empty body) degrades to an empty/null result so one
 * bad page never aborts a sweep cycle. Zero-dep HttpServer double, mirroring
 * the platform client tests.
 */
class SearchSourceClientTest {

    private HttpServer server;
    private String baseUrl;
    private int statusOnPublic = 200;
    private String publicBody = "{\"page\":0,\"content\":[{\"id\":5,\"name\":\"Spice\",\"active\":true}]}";
    private String menuBody = "{\"restaurantId\":5,\"restaurantName\":\"Spice\",\"items\":"
            + "[{\"id\":50,\"name\":\"Butter Chicken\",\"price\":320.0,\"available\":true}]}";
    private String restaurantBody = "{\"id\":5,\"name\":\"Spice\",\"description\":\"d\",\"active\":true}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/restaurants/public/", exchange -> {
            byte[] body = restaurantBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusOnPublic, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/v1/restaurants/public", exchange -> {
            byte[] body = publicBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusOnPublic, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/v1/restaurants/", exchange -> {
            byte[] body = menuBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusOnPublic, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private SearchSourceClient client() {
        return new SearchSourceClient(baseUrl);
    }

    @Test
    void restaurantPage_returnsContentRows() {
        var page = client().restaurantPage(0, 100);

        assertThat(page).singleElement()
                .extracting(SourceRestaurant::id).isEqualTo(5L);
    }

    @Test
    void restaurantPage_nullContentBody_degradesToEmpty() {
        publicBody = "{\"page\":1,\"content\":null}";

        assertThat(client().restaurantPage(1, 20)).isEmpty();
    }

    @Test
    void restaurantPage_upstream500_degradesToEmpty() {
        statusOnPublic = 500;

        assertThat(client().restaurantPage(0, 20)).isEmpty();
    }

    @Test
    void restaurantPage_deadHost_degradesToEmpty() {
        int deadPort = server.getAddress().getPort();
        server.stop(0);

        assertThat(new SearchSourceClient("http://localhost:" + deadPort)
                .restaurantPage(0, 20)).isEmpty();
    }

    @Test
    void menu_returnsSnapshot() {
        SourceMenu menu = client().menu(5L);

        assertThat(menu).isNotNull();
        assertThat(menu.restaurantId()).isEqualTo(5L);
        assertThat(menu.restaurantName()).isEqualTo("Spice");
        assertThat(menu.items()).singleElement()
                .extracting(SourceMenuItem::name).isEqualTo("Butter Chicken");
    }

    @Test
    void menu_nullItems_defaultsToEmptyList() {
        menuBody = "{\"restaurantId\":9}";

        assertThat(client().menu(9L).items()).isEmpty();
    }

    @Test
    void menu_missingResource_degradesToNull() {
        statusOnPublic = 404;
        menuBody = "{}";

        assertThat(client().menu(404L)).isNull();
    }

    @Test
    void restaurant_singleProfileLookup() {
        SourceRestaurant restaurant = client().restaurant(5L);

        assertThat(restaurant).isNotNull();
        assertThat(restaurant.name()).isEqualTo("Spice");
        assertThat(restaurant.active()).isTrue();
    }

    @Test
    void restaurant_upstreamBroken_degradesToNull() {
        int deadPort = server.getAddress().getPort();
        server.stop(0);

        assertThat(new SearchSourceClient("http://localhost:" + deadPort).restaurant(5L)).isNull();
    }

    @Test
    void menusBounded_returnsSnapshotsForAllIds() {
        List<SourceMenu> menus = client().menusBounded(List.of(5L, 5L), 2);

        assertThat(menus).hasSize(2);
        assertThat(menus.get(0)).isNotNull();
        assertThat(menus.get(1)).isNotNull();
    }

    @Test
    void menusBounded_emptyList_returnsEmpty() {
        List<SourceMenu> menus = client().menusBounded(List.of(), 2);

        assertThat(menus).isEmpty();
    }

    @Test
    void menusBounded_upstreamFailure_returnsNullForFailedId() {
        int deadPort = server.getAddress().getPort();
        server.stop(0);

        List<SourceMenu> menus = new SearchSourceClient("http://localhost:" + deadPort)
                .menusBounded(List.of(1L, 2L), 2);

        assertThat(menus).hasSize(2);
        assertThat(menus.get(0)).isNull();
        assertThat(menus.get(1)).isNull();
    }
}
