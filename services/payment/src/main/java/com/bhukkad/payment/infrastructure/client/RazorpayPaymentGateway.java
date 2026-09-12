package com.bhukkad.payment.infrastructure.client;

import com.bhukkad.payment.PaymentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.bhukkad.payment.PaymentProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Razorpay PSP adapter (audit feature #1): raw HTTP against the Razorpay
 * Orders/Payments API — no SDK dependency (roadmap feature #1 dependency
 * analysis: "prefer raw HTTP to keep the dependency surface small").
 *
 * <p>Charge flow ({@link #authorize}): create a PSP order, then poll the
 * order's payments until one is {@code captured}; an {@code authorized}
 * payment is captured server-side (poll/capture to charged). {@code refund}
 * posts a partial/full refund against the captured payment. Every call runs
 * on the payment-local {@code razorpayWebClient} bean, which applies the
 * SAME platform-lib resilience stack as the shared WebClient factory
 * ({@code RetryFilter} for transient GET failures, Resilience4j
 * {@code CircuitBreakerFilter} with exported breaker gauges) — money-moving
 * POSTs are never retried by the filter (PERF-1/B5), so this adapter is safe
 * to call with its own idempotency key upstream ({@code PAYMENT_CHARGE}).
 *
 * <p>Amounts travel in paise (Razorpay contract); the adapter converts from
 * the service's BigDecimal rupee convention at the boundary.
 */
@Slf4j
public class RazorpayPaymentGateway implements PaymentGateway {

    /** Bounded poll budget for the order-payments sweep; each miss sleeps. */
    static final int MAX_POLL_ATTEMPTS = 5;
    static final long POLL_SLEEP_MILLIS = 200L;
    private static final String STATUS_CAPTURED = "captured";
    private static final String STATUS_AUTHORIZED = "authorized";

    private final WebClient webClient;
    private final PaymentProperties.Razorpay config;
    private final PaymentRefResolver refResolver;

    /** Resolves the internal payment id to its PSP payment reference for refunds. */
    public interface PaymentRefResolver {
        String providerPaymentRef(Long paymentId);
    }

    public RazorpayPaymentGateway(WebClient razorpayWebClient,
                                  PaymentProperties paymentProperties,
                                  PaymentRefResolver refResolver) {
        this.webClient = razorpayWebClient;
        this.config = paymentProperties.getRazorpay();
        this.refResolver = refResolver;
    }

    @Override
    public GatewayResult authorize(Long paymentId, Long customerId, BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            return GatewayResult.failed("Charge amount must be positive");
        }
        try {
            String gatewayOrderId = createOrder(paymentId, amount, currency);
            String paymentRef = pollAndCapture(gatewayOrderId, amount, currency);
            if (paymentRef == null) {
                return GatewayResult.failed(
                        "No captured payment for gateway order " + gatewayOrderId + " within the poll budget");
            }
            return GatewayResult.ok(paymentRef, gatewayOrderId);
        } catch (WebClientResponseException e) {
            log.warn("RAZORPAY_CHARGE_REJECTED | paymentId={} | status={} | body={}",
                    paymentId, e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
            return GatewayResult.failed("Razorpay rejected the charge: HTTP "
                    + e.getStatusCode().value());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GatewayResult.failed("Razorpay charge poll interrupted");
        }
    }

    @Override
    public GatewayResult refund(Long paymentId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return GatewayResult.failed("Refund amount must be positive");
        }
        String providerPaymentRef = refResolver.providerPaymentRef(paymentId);
        if (providerPaymentRef == null || providerPaymentRef.isBlank()) {
            return GatewayResult.failed("No provider payment reference for payment " + paymentId);
        }
        try {
            JsonNode refund = webClient.post()
                    .uri("/payments/{ref}/refund", providerPaymentRef)
                    .headers(this::applyAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"amount\":" + toPaise(amount) + "}")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            String refundId = refund == null ? null : refund.path("id").asText(null);
            if (refundId == null) {
                return GatewayResult.failed("Razorpay refund response without an id");
            }
            return GatewayResult.ok(refundId);
        } catch (WebClientResponseException e) {
            log.warn("RAZORPAY_REFUND_REJECTED | paymentId={} | status={} | body={}",
                    paymentId, e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
            return GatewayResult.failed("Razorpay rejected the refund: HTTP "
                    + e.getStatusCode().value());
        }
    }

    // ── Razorpay API steps ───────────────────────────────────────────────────

    private String createOrder(Long paymentId, BigDecimal amount, String currency) {
        JsonNode order = webClient.post()
                .uri("/orders")
                .headers(this::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":" + toPaise(amount)
                        + ",\"currency\":\"" + currency
                        + "\",\"receipt\":\"pay-" + paymentId + "\"}")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        String orderId = order == null ? null : order.path("id").asText(null);
        if (orderId == null) {
            throw new IllegalStateException("Razorpay order response without an id");
        }
        return orderId;
    }

    /**
     * Polls {@code GET /orders/{id}/payments} up to {@value #MAX_POLL_ATTEMPTS}
     * times: returns the first {@code captured} payment; an {@code authorized}
     * one is captured server-side first (poll/capture to charged).
     */
    private String pollAndCapture(String gatewayOrderId, BigDecimal amount, String currency)
            throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            JsonNode items = listOrderPayments(gatewayOrderId);
            String captured = firstWithStatus(items, STATUS_CAPTURED);
            if (captured != null) {
                return captured;
            }
            String authorized = firstWithStatus(items, STATUS_AUTHORIZED);
            if (authorized != null) {
                String capturedRef = capture(authorized, amount, currency);
                if (capturedRef != null) {
                    return capturedRef;
                }
            }
            if (attempt < MAX_POLL_ATTEMPTS) {
                Thread.sleep(POLL_SLEEP_MILLIS);
            }
        }
        return null;
    }

    private JsonNode listOrderPayments(String gatewayOrderId) {
        return webClient.get()
                .uri("/orders/{id}/payments", gatewayOrderId)
                .headers(this::applyAuth)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    private String capture(String paymentRef, BigDecimal amount, String currency) {
        try {
            JsonNode payment = webClient.post()
                    .uri("/payments/{ref}/capture", paymentRef)
                    .headers(this::applyAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"amount\":" + toPaise(amount) + ",\"currency\":\"" + currency + "\"}")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return payment != null && STATUS_CAPTURED.equals(payment.path("status").asText())
                    ? payment.path("id").asText(paymentRef)
                    : null;
        } catch (WebClientResponseException e) {
            log.warn("RAZORPAY_CAPTURE_REJECTED | paymentRef={} | status={}",
                    paymentRef, e.getStatusCode().value());
            return null;
        }
    }

    private String firstWithStatus(JsonNode paymentsResponse, String status) {
        if (paymentsResponse == null) {
            return null;
        }
        // Razorpay returns {"count":N,"items":[…]} for order payment listings;
        // tolerate a bare array in case of schema drift.
        JsonNode items = paymentsResponse.isArray()
                ? paymentsResponse
                : paymentsResponse.path("items");
        if (!items.isArray()) {
            return null;
        }
        for (JsonNode payment : items) {
            if (status.equals(payment.path("status").asText())) {
                return payment.path("id").asText(null);
            }
        }
        return null;
    }

    private void applyAuth(HttpHeaders headers) {
        String credentials = config.getKeyId() + ":" + config.getKeySecret();
        headers.setBasicAuth(Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }

    private static long toPaise(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }
}
