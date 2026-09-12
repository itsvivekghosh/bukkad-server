package com.bhukkad.realtime.infrastructure.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G-13 migration contract pins for the platform-factory OrderOwnershipClient:
 * the migrated client must keep the exact mesh surface (path,
 * {@code X-Service-Token} stamping) and the fail-closed verdicts the
 * {@code LiveStreamController} relies on (matched owner → true; 404/wrong
 * owner/no token/outage → false — never an exception leaking to the
 * subscribe path). Zero-dep {@code HttpServer} test-double style, mirroring
 * the order service's RestaurantClientTest.
 */
class OrderOwnershipClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> seenTokens = new ArrayList<>();
    private final List<String> seenPaths = new ArrayList<>();
    private final Set<String> respond404For = new java.util.HashSet<>();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            seenTokens.add(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            seenPaths.add(exchange.getRequestURI().getPath());
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (respond404For.stream().anyMatch(path::endsWith)) {
                body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); // real order service is JSON
                exchange.sendResponseHeaders(404, body.length);
            } else {
                long owner = path.endsWith("/100/customer") ? 5L : 9L;
                body = ("{\"customerId\":" + owner + ",\"orderNumber\":\"ORD-X\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
            }
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

    private OrderOwnershipClient clientWithMeshAuth() {
        ServiceJwtAuthTokenProvider provider = mock(ServiceJwtAuthTokenProvider.class);
        when(provider.serviceToken()).thenReturn("svc-token");
        when(tokenProvider.getIfAvailable()).thenReturn(provider);
        when(meterProvider.getIfAvailable()).thenReturn(null);
        return new OrderOwnershipClient(baseUrl, tokenProvider, meterProvider);
    }

    @Test
    void matchedOwner_returnsTrue_withServiceTokenAndInternalPath() {
        assertThat(clientWithMeshAuth().ownsOrder(5L, 100L)).isTrue();
        assertThat(seenPaths).containsExactly("/api/v1/internal/orders/100/customer");
        assertThat(seenTokens).containsExactly("svc-token"); // header name + value unchanged
    }

    @Test
    void differentOwner_returnsFalse() {
        assertThat(clientWithMeshAuth().ownsOrder(5L, 200L)).isFalse();
    }

    @Test
    void upstreamNotFound_failsClosed_asNotOwned() {
        respond404For.add("/999/customer");
        assertThat(clientWithMeshAuth().ownsOrder(5L, 999L)).isFalse();
    }

    @Test
    void missingMeshToken_deniesWithoutHttpCall() {
        when(tokenProvider.getIfAvailable()).thenReturn(null);
        OrderOwnershipClient client = new OrderOwnershipClient(baseUrl, tokenProvider, meterProvider);
        assertThat(client.ownsOrder(5L, 100L)).isFalse();
        assertThat(seenPaths).isEmpty();
    }

    @Test
    void upstreamDown_failsClosed() {
        int deadPort = server.getAddress().getPort();
        stopServer(); // connection refused on the recorded port
        OrderOwnershipClient client = clientWithMeshAuth();
        // baseUrl rebuilt against the now-dead port (fresh instance, same mock wiring)
        OrderOwnershipClient downClient = new OrderOwnershipClient(
                "http://localhost:" + deadPort, tokenProvider, meterProvider);
        assertThat(downClient.ownsOrder(5L, 100L)).isFalse();
        assertThat(client.ownsOrder(null, 100L)).isFalse(); // null principal short-circuits
    }
}
