package com.bhukkad.payment.infrastructure.client;

import com.bhukkad.payment.PaymentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PSP adapter unit tests against a mocked HTTP layer. Follows the repo's
 * zero-dep {@code HttpServer} test-double style (PaymentServiceClientTest /
 * RestaurantClientTest) — no new test infrastructure.
 */
class RazorpayPaymentGatewayTest {

    private HttpServer server;
    private String baseUrl;
    private RazorpayPaymentGateway gateway;
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        PaymentProperties properties = new PaymentProperties();
        properties.setGateway("razorpay");
        properties.getRazorpay().setBaseUrl(baseUrl);
        properties.getRazorpay().setKeyId("key_test");
        properties.getRazorpay().setKeySecret("secret_test");
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getRazorpay().getBaseUrl())
                .filter(new com.bhukkad.common.web.client.RetryFilter(3, java.time.Duration.ofMillis(50)))
                .build();
        gateway = new RazorpayPaymentGateway(webClient, properties,
                paymentId -> "pay_" + paymentId);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Serves every registered path with the last stubbed response; records requests. */
    private void route(String path, int status, String body) {
        server.createContext(path, exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.Map<String, Object> record = new java.util.HashMap<>();
            record.put("method", exchange.getRequestMethod());
            record.put("path", exchange.getRequestURI().getPath());
            record.put("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            record.put("body", requestBody);
            requests.add(record);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private static final String ORDER_RESPONSE = "{\"id\":\"order_1\"}";
    private static final String CAPTURED_PAYMENT =
            "{\"count\":1,\"items\":[{\"id\":\"pay_1\",\"status\":\"captured\"}]}";
    private static final String AUTHORIZED_PAYMENT =
            "{\"count\":1,\"items\":[{\"id\":\"pay_9\",\"status\":\"authorized\"}]}";

    @Test
    void authorize_capturedOnFirstPoll_createsOrderThenCharges() {
        route("/orders", 200, ORDER_RESPONSE);
        route("/orders/order_1/payments", 200, CAPTURED_PAYMENT);

        PaymentGateway.GatewayResult result = gateway.authorize(
                1L, 2L, new BigDecimal("100.00"), "INR");

        assertThat(result.success()).isTrue();
        assertThat(result.providerRef()).isEqualTo("pay_1");
        assertThat(result.gatewayOrderId()).isEqualTo("order_1");
        // Auth is Basic key:secret per Razorpay contract.
        assertThat(requests.get(0).get("path")).isEqualTo("/orders");
        String expectedAuth = java.util.Base64.getEncoder()
                .encodeToString("key_test:secret_test".getBytes(StandardCharsets.UTF_8));
        assertThat(requests.get(0).get("auth")).isEqualTo("Basic " + expectedAuth);
        // Amount travels in paise.
        assertThat((String) requests.get(0).get("body")).contains("\"amount\":10000");
    }

    @Test
    void authorize_authorizedPayment_capturedViaServerSideCapture() {
        route("/orders", 200, ORDER_RESPONSE);
        route("/orders/order_1/payments", 200, AUTHORIZED_PAYMENT);
        route("/payments/pay_9/capture", 200, "{\"id\":\"pay_9\",\"status\":\"captured\"}");

        PaymentGateway.GatewayResult result = gateway.authorize(
                1L, 2L, new BigDecimal("250.50"), "INR");

        assertThat(result.success()).isTrue();
        assertThat(result.providerRef()).isEqualTo("pay_9");
        assertThat(result.gatewayOrderId()).isEqualTo("order_1");
        assertThat(requests.stream().anyMatch(r -> "/payments/pay_9/capture".equals(r.get("path"))))
                .as("server-side capture issued").isTrue();
    }

    @Test
    void authorize_noCapturedPaymentWithinBudget_failsCleanly() {
        route("/orders", 200, ORDER_RESPONSE);
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/orders/order_1/payments", exchange -> {
            polls.incrementAndGet();
            byte[] bytes = "{\"count\":0,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        PaymentGateway.GatewayResult result = gateway.authorize(
                1L, 2L, new BigDecimal("100.00"), "INR");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("No captured payment");
        assertThat(polls.get()).isEqualTo(RazorpayPaymentGateway.MAX_POLL_ATTEMPTS);
    }

    @Test
    void authorize_pspRejection_mapsToFailingResult() {
        route("/orders", 402, "{\"error\":{\"description\":\"payment declined\"}}");

        PaymentGateway.GatewayResult result = gateway.authorize(
                1L, 2L, new BigDecimal("100.00"), "INR");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("402");
        assertThat(result.providerRef()).isNull();
    }

    @Test
    void authorize_nonPositiveAmount_failsWithoutNetworkCall() {
        int before = requests.size();
        PaymentGateway.GatewayResult result = gateway.authorize(
                1L, 2L, BigDecimal.ZERO, "INR");
        assertThat(result.success()).isFalse();
        assertThat(requests.size()).isEqualTo(before);
    }

    @Test
    void refund_againstCapturedPayment_postsPaiseAmount() {
        route("/payments/pay_42/refund", 200, "{\"id\":\"rfnd_1\"}");

        PaymentGateway.GatewayResult result = gateway.refund(42L, new BigDecimal("99.99"));

        assertThat(result.success()).isTrue();
        assertThat(result.providerRef()).isEqualTo("rfnd_1");
        Map<String, Object> refundRequest = requests.get(0);
        assertThat(refundRequest.get("path")).isEqualTo("/payments/pay_42/refund");
        assertThat((String) refundRequest.get("body")).contains("\"amount\":9999");
    }

    @Test
    void refund_withoutProviderRef_failsWithoutNetworkCall() {
        RazorpayPaymentGateway gatewayWithoutRef = new RazorpayPaymentGateway(
                WebClient.create(),
                new PaymentProperties(),
                paymentId -> null);

        PaymentGateway.GatewayResult result = gatewayWithoutRef.refund(1L, new BigDecimal("10.00"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("No provider payment reference");
    }

    @Test
    void refund_pspRejection_mapsToFailingResult() {
        route("/payments/pay_42/refund", 400, "{\"error\":{\"description\":\"already refunded\"}}");

        PaymentGateway.GatewayResult result = gateway.refund(42L, new BigDecimal("10.00"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400");
    }
}
