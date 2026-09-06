package com.bhukkad.order.client;

import com.bhukkad.order.client.dto.ChargeResponse;
import com.bhukkad.order.client.dto.RefundResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the order-saga payment client (audit batch A) against the
 * server contract the payment service exposes on {@code /api/v1/internal/
 * payments/*}: JSON-body charge with {@code X-Service-Token}, {@code CHARGED}
 * receipts, and refund compensation. Follows RestaurantClientTest's zero-dep
 * {@link HttpServer} style.
 */
class PaymentServiceClientTest {

    private HttpServer server;
    private String baseUrl;
    private PaymentServiceClient client;
    private final List<Map<String, Object>> received = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        client = new PaymentServiceClient(baseUrl);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(int status, String body) {
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, Object> record = new java.util.HashMap<>();
            record.put("path", path);
            record.put("token", exchange.getRequestHeaders().getFirst("X-Service-Token"));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            record.put("body", requestBody);
            received.add(record);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    void charge_confirmed_returnsReceiptAndSendsContractBody() {
        respond(200, "{\"paymentId\":5,\"status\":\"CHARGED\"}");

        ChargeResponse response = client.charge(1L, 2L, new BigDecimal("100.00"),
                "WALLET", "ORDER-1", "service-jwt").block();

        assertThat(response).isNotNull();
        assertThat(response.paymentId()).isEqualTo(5L);
        assertThat(response.status()).isEqualTo("CHARGED");

        Map<String, Object> req = received.get(0);
        assertThat(req.get("path")).isEqualTo("/api/v1/internal/payments/charge");
        assertThat(req.get("token")).isEqualTo("service-jwt");
        assertThat((String) req.get("body")).contains("\"orderId\":1")
                .contains("\"customerId\":2")
                .contains("\"amount\":\"100.00\"")
                .contains("\"paymentMethod\":\"WALLET\"")
                .contains("\"reference\":\"ORDER-1\"");
    }

    @Test
    void charge_omitsTokenHeaderWhenNotConfigured() {
        respond(200, "{\"paymentId\":5,\"status\":\"CHARGED\"}");

        client.charge(1L, 2L, new BigDecimal("10.00"), "WALLET", "ORDER-1", null).block();

        assertThat(received.get(0).get("token")).isNull();
    }

    @Test
    void charge_http4xxFails_theMonoErrors() {
        respond(402, "{\"error\":\"insufficient funds\"}");

        assertThatThrownBy(() -> client.charge(1L, 2L, new BigDecimal("10.00"),
                "WALLET", "ORDER-1", "t").block())
                .isInstanceOf(WebClientResponseException.class);
    }

    @Test
    void charge_pendingStatusWithPaymentId_isIgnoredSoStepFails() {
        // 202-style receipts (status != CHARGED) must NOT satisfy the charge —
        // the filtered Mono completes empty and the saga step fails.
        respond(200, "{\"paymentId\":5,\"status\":\"PENDING\"}");

        ChargeResponse response = client.charge(1L, 2L, new BigDecimal("10.00"),
                "WALLET", "ORDER-1", "t").block();

        assertThat(response).isNull();
    }

    @Test
    void refund_confirmed_returnsRefundedReceipt() {
        respond(200, "{\"status\":\"REFUNDED\"}");

        RefundResponse response = client.refund(5L, "saga-compensation", "service-jwt").block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("REFUNDED");
        Map<String, Object> req = received.get(0);
        assertThat(req.get("path")).isEqualTo("/api/v1/internal/payments/5/refund");
        assertThat(req.get("token")).isEqualTo("service-jwt");
        assertThat((String) req.get("body")).contains("\"reason\":\"saga-compensation\"");
    }

    @Test
    void refund_http5xxFails_theMonoErrors() {
        respond(500, "{\"error\":\"settlement engine offline\"}");

        assertThatThrownBy(() -> client.refund(5L, "saga-compensation", "t").block())
                .isInstanceOf(WebClientResponseException.class);
    }
}
