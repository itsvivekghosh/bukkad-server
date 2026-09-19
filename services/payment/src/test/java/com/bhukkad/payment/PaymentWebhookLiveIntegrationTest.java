package com.bhukkad.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live HTTP integration test for Razorpay webhook processing.
 *
 * <p>Proves the full controller → service → DB contract:</p>
 * <ul>
 *   <li>a valid signed {@code payment.captured} webhook transitions a PENDING
 *       payment to SETTLED and enqueues outbox events;</li>
 *   <li>re-delivering the same event is idempotent (no double-apply);</li>
 *   <li>invalid signatures are rejected before touching the database.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentWebhookLiveIntegrationTest extends AbstractPaymentPostgresTest {

    private static final String WEBHOOK_SECRET = "dev-webhook-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private RestTemplate restTemplate;
    private String webhookBaseUrl;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        webhookBaseUrl = "http://localhost:" + port + "/api/v1/payments/webhooks/razorpay";

        // Clean slate
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM payments");
    }

    private void seedPendingPayment() {
        jdbcTemplate.update(
                "INSERT INTO payments (order_id, customer_id, amount, currency, status, "
                        + "provider, provider_ref, gateway_order_id, gateway_payment_id, "
                        + "created_at, updated_at) "
                        + "VALUES (100, 200, 49.00, 'INR', 'PENDING', 'RAZORPAY', 'PAY-LIVE-1', "
                        + "'ORDER-LIVE-1', 'PAY-LIVE-1', localtimestamp, localtimestamp)");
    }

    @Test
    void signedCapturePayload_setsPaymentToSettled_andEnqueuesOutbox() throws Exception {
        seedPendingPayment();

        String payload = objectMapper.writeValueAsString(Map.of(
                "event", "payment.captured",
                "payload", Map.of("payment", Map.of(
                        "entity", Map.of("id", "PAY-LIVE-1", "order_id", "ORDER-LIVE-1")))
        ));
        String signature = hmacSha256(payload, WEBHOOK_SECRET);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Razorpay-Signature", signature);
        headers.set("X-Razorpay-Event-Id", "evt-live-capture-1");

        HttpEntity<String> request = new HttpEntity<>(payload, headers);
        org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(
                webhookBaseUrl, HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Webhook processed");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE provider_ref = 'PAY-LIVE-1'", String.class);
        assertThat(status).isEqualTo("SETTLED");

        // completeWebhookPayment enqueues PAYMENT_SETTLED and the webhook path
        // enqueues PAYMENT_WEBHOOK_RECEIVED, so 2 outbox rows are expected.
        Long outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events", Long.class);
        assertThat(outboxCount).isEqualTo(2);
    }

    @Test
    void duplicateCapturePayload_isIdempotent() throws Exception {
        seedPendingPayment();

        String payload = objectMapper.writeValueAsString(Map.of(
                "event", "payment.captured",
                "payload", Map.of("payment", Map.of(
                        "entity", Map.of("id", "PAY-LIVE-1", "order_id", "ORDER-LIVE-1")))
        ));
        String signature = hmacSha256(payload, WEBHOOK_SECRET);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Razorpay-Signature", signature);
        headers.set("X-Razorpay-Event-Id", "evt-live-capture-dup");

        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        // First delivery — processes
        org.springframework.http.ResponseEntity<String> first = restTemplate.exchange(
                webhookBaseUrl, HttpMethod.POST, request, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second delivery — must be idempotent
        org.springframework.http.ResponseEntity<String> second = restTemplate.exchange(
                webhookBaseUrl, HttpMethod.POST, request, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo("Webhook duplicate ignored");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE provider_ref = 'PAY-LIVE-1'", String.class);
        assertThat(status).isEqualTo("SETTLED");

        Long outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events", Long.class);
        assertThat(outboxCount).isEqualTo(2);
    }

    @Test
    void invalidSignature_isRejected() throws Exception {
        seedPendingPayment();

        String payload = objectMapper.writeValueAsString(Map.of(
                "event", "payment.captured",
                "payload", Map.of("payment", Map.of(
                        "entity", Map.of("id", "PAY-LIVE-1", "order_id", "ORDER-LIVE-1")))
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Razorpay-Signature", "invalid-signature");
        headers.set("X-Razorpay-Event-Id", "evt-live-bad-sig");

        HttpEntity<String> request = new HttpEntity<>(payload, headers);
        try {
            restTemplate.exchange(
                    webhookBaseUrl, HttpMethod.POST, request, String.class);
            throw new AssertionError("Expected 400 Bad Request for invalid signature");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getResponseBodyAsString()).isEqualTo("Invalid signature");
        }

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE provider_ref = 'PAY-LIVE-1'", String.class);
        assertThat(status).isEqualTo("PENDING");
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
