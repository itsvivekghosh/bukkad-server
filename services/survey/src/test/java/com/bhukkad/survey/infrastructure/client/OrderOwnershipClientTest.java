package com.bhukkad.survey.infrastructure.client;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G-13 migration contract pins for the survey platform-factory
 * OrderOwnershipClient: identical mesh surface (internal ownership path,
 * {@code X-Service-Token}) and the SV-1 fail-closed semantics
 * {@code SurveyServiceImpl} depends on — no answer, wrong owner, no token or
 * an outage all deny eligibility; nothing throws. Zero-dep {@code HttpServer}
 * test-double style, mirroring the order service's client tests.
 */
class OrderOwnershipClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> seenTokens = new ArrayList<>();
    private final List<String> seenPaths = new ArrayList<>();
    private final Set<String> respond404For = new HashSet<>();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            seenTokens.add(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            String path = exchange.getRequestURI().getPath();
            seenPaths.add(path);
            byte[] body;
            if (respond404For.stream().anyMatch(path::endsWith)) {
                body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); // real order service is JSON
                exchange.sendResponseHeaders(404, body.length);
            } else {
                long owner = path.endsWith("/300/customer") ? 5L : 9L;
                body = ("{\"customerId\":" + owner + "}").getBytes(StandardCharsets.UTF_8);
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
    void matchedOwner_isEligible_withServiceTokenAndInternalPath() {
        assertThat(clientWithMeshAuth().ownsOrder(5L, 300L)).isTrue();
        assertThat(seenPaths).containsExactly("/api/v1/internal/orders/300/customer");
        assertThat(seenTokens).containsExactly("svc-token");
    }

    @Test
    void foreignOrder_deniesEligibility() {
        assertThat(clientWithMeshAuth().ownsOrder(5L, 400L)).isFalse();
    }

    @Test
    void upstreamNotFound_deniesAsNotOwned() {
        respond404For.add("/777/customer");
        assertThat(clientWithMeshAuth().ownsOrder(5L, 777L)).isFalse();
    }

    @Test
    void missingMeshToken_deniesWithoutHttpCall() {
        when(tokenProvider.getIfAvailable()).thenReturn(null);
        assertThat(new OrderOwnershipClient(baseUrl, tokenProvider, meterProvider)
                .ownsOrder(5L, 300L)).isFalse();
        assertThat(seenPaths).isEmpty();
    }

    @Test
    void upstreamOutage_deniesEligibility() {
        int deadPort = server.getAddress().getPort();
        stopServer(); // connection refused on the recorded port
        ServiceJwtAuthTokenProvider provider = mock(ServiceJwtAuthTokenProvider.class);
        when(provider.serviceToken()).thenReturn("svc-token");
        when(tokenProvider.getIfAvailable()).thenReturn(provider);
        when(meterProvider.getIfAvailable()).thenReturn(null);
        OrderOwnershipClient downClient = new OrderOwnershipClient(
                "http://localhost:" + deadPort, tokenProvider, meterProvider);
        assertThat(downClient.ownsOrder(5L, 300L)).isFalse();
        assertThat(downClient.ownsOrder(null, 300L)).isFalse(); // null principal short-circuits
    }
}
