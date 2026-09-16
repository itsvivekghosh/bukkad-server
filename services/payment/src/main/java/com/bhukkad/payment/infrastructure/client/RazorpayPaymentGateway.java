package com.bhukkad.payment.infrastructure.client;

import com.bhukkad.payment.PaymentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import reactor.core.publisher.Mono;

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
 * the service's BigDecimal rupee convention at the boundary.</p>
 *
 * <p><strong>Reactive contract:</strong> all methods return {@link Mono} so
 * the adapter is fully non-blocking. Callers at the service/controller
 * boundary decide whether to {@code block()} (Servlet threads) or stay
 * reactive (WebFlux pipelines).</p>
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
    public Mono<GatewayResult> authorize(Long paymentId, Long customerId, BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            return Mono.just(GatewayResult.failed("Charge amount must be positive"));
        }
        return createOrder(paymentId, amount, currency)
                .flatMap(gatewayOrderId -> pollAndCapture(gatewayOrderId, amount, currency)
                        .map(paymentRef -> GatewayResult.ok(paymentRef, gatewayOrderId))
                        .onErrorResume(e -> {
                            log.warn("RAZORPAY_CHARGE_FAILED | paymentId={} | error={}", paymentId, e.getMessage());
                            return Mono.just(GatewayResult.failed("Razorpay charge failed: " + e.getMessage()));
                        }))
                .doOnNext(result -> {
                    if (result.success()) {
                        log.info("RAZORPAY_CHARGE_OK | paymentId={} | providerRef={}", paymentId, result.providerRef());
                    }
                })
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.warn("RAZORPAY_CHARGE_REJECTED | paymentId={} | status={} | body={}",
                            paymentId, e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
                    return Mono.just(GatewayResult.failed("Razorpay rejected the charge: HTTP " + e.getStatusCode().value()));
                })
                .onErrorResume(e -> {
                    log.error("RAZORPAY_CHARGE_ERROR | paymentId={}", paymentId, e);
                    return Mono.just(GatewayResult.failed("Gateway error: " + e.getMessage()));
                });
    }

    @Override
    public Mono<GatewayResult> refund(Long paymentId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return Mono.just(GatewayResult.failed("Refund amount must be positive"));
        }
        String providerPaymentRef = refResolver.providerPaymentRef(paymentId);
        if (providerPaymentRef == null || providerPaymentRef.isBlank()) {
            return Mono.just(GatewayResult.failed("No provider payment reference for payment " + paymentId));
        }
        return webClient.post()
                .uri("/payments/{ref}/refund", providerPaymentRef)
                .headers(this::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":" + toPaise(amount) + "}")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(refund -> {
                    String refundId = refund == null ? null : refund.path("id").asText(null);
                    if (refundId == null) {
                        return GatewayResult.failed("Razorpay refund response without an id");
                    }
                    return GatewayResult.ok(refundId);
                })
                .doOnSuccess(result -> log.info("RAZORPAY_REFUND_OK | paymentId={} | refundId={}",
                        paymentId, result.providerRef()))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.warn("RAZORPAY_REFUND_REJECTED | paymentId={} | status={} | body={}",
                            paymentId, e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
                    return Mono.just(GatewayResult.failed("Razorpay rejected the refund: HTTP " + e.getStatusCode().value()));
                })
                .onErrorResume(e -> {
                    log.error("RAZORPAY_REFUND_ERROR | paymentId={}", paymentId, e);
                    return Mono.just(GatewayResult.failed("Gateway error: " + e.getMessage()));
                });
    }

    // ── Razorpay API steps ───────────────────────────────────────────────────

    private Mono<String> createOrder(Long paymentId, BigDecimal amount, String currency) {
        String body = "{\"amount\":" + toPaise(amount)
                + ",\"currency\":\"" + currency
                + "\",\"receipt\":\"pay-" + paymentId + "\"}";
        return webClient.post()
                .uri("/orders")
                .headers(this::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(order -> {
                    String orderId = order == null ? null : order.path("id").asText(null);
                    if (orderId == null) {
                        throw new IllegalStateException("Razorpay order response without an id");
                    }
                    return orderId;
                });
    }

    /**
     * Polls {@code GET /orders/{id}/payments} up to {@value #MAX_POLL_ATTEMPTS}
     * times: returns the first {@code captured} payment; an {@code authorized}
     * one is captured server-side first (poll/capture to charged).
     */
    private Mono<String> pollAndCapture(String gatewayOrderId, BigDecimal amount, String currency) {
        return pollAndCapture(gatewayOrderId, amount, currency, 0);
    }

    private Mono<String> pollAndCapture(String gatewayOrderId, BigDecimal amount, String currency, int attempt) {
        if (attempt >= MAX_POLL_ATTEMPTS) {
            return Mono.error(new IllegalStateException(
                    "No captured payment for gateway order " + gatewayOrderId + " within the poll budget"));
        }
        return listOrderPayments(gatewayOrderId)
                .flatMap(items -> findCaptured(items)
                        .switchIfEmpty(findAuthorizedAndCapture(items, gatewayOrderId, amount, currency, attempt)))
                .switchIfEmpty(retryPoll(gatewayOrderId, amount, currency, attempt));
    }

    private Mono<String> findCaptured(JsonNode items) {
        return firstWithStatus(items, STATUS_CAPTURED);
    }

    private Mono<String> findAuthorizedAndCapture(JsonNode items, String gatewayOrderId,
                                                   BigDecimal amount, String currency, int attempt) {
        return firstWithStatus(items, STATUS_AUTHORIZED)
                .flatMap(authorized -> capture(authorized, amount, currency)
                        .onErrorResume(e -> {
                            log.warn("RAZORPAY_CAPTURE_FAILED | gatewayOrderId={} | error={}",
                                    gatewayOrderId, e.getMessage());
                            return Mono.empty();
                        })
                        .filter(capturedRef -> capturedRef != null))
                .switchIfEmpty(Mono.defer(() -> {
                    if (attempt < MAX_POLL_ATTEMPTS - 1) {
                        return Mono.delay(Duration.ofMillis(POLL_SLEEP_MILLIS))
                                .then(pollAndCapture(gatewayOrderId, amount, currency, attempt + 1));
                    }
                    return Mono.empty();
                }));
    }

    private Mono<String> retryPoll(String gatewayOrderId, BigDecimal amount, String currency, int attempt) {
        return Mono.defer(() -> {
            if (attempt < MAX_POLL_ATTEMPTS - 1) {
                return Mono.delay(Duration.ofMillis(POLL_SLEEP_MILLIS))
                        .then(pollAndCapture(gatewayOrderId, amount, currency, attempt + 1));
            }
            return Mono.error(new IllegalStateException(
                    "No captured payment for gateway order " + gatewayOrderId + " within the poll budget"));
        });
    }

    private Mono<JsonNode> listOrderPayments(String gatewayOrderId) {
        return webClient.get()
                .uri("/orders/{id}/payments", gatewayOrderId)
                .headers(this::applyAuth)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    private Mono<String> capture(String paymentRef, BigDecimal amount, String currency) {
        String body = "{\"amount\":" + toPaise(amount) + ",\"currency\":\"" + currency + "\"}";
        return webClient.post()
                .uri("/payments/{ref}/capture", paymentRef)
                .headers(this::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(payment -> {
                    boolean captured = payment != null && STATUS_CAPTURED.equals(payment.path("status").asText());
                    return captured ? payment.path("id").asText(paymentRef) : null;
                })
                .doOnSuccess(result -> log.info("RAZORPAY_CAPTURE_OK | paymentRef={}", paymentRef))
                .onErrorResume(e -> {
                    log.warn("RAZORPAY_CAPTURE_REJECTED | paymentRef={} | error={}", paymentRef, e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<String> firstWithStatus(JsonNode paymentsResponse, String status) {
        if (paymentsResponse == null) {
            return Mono.empty();
        }
        JsonNode items = paymentsResponse.isArray()
                ? paymentsResponse
                : paymentsResponse.path("items");
        if (!items.isArray()) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            for (JsonNode payment : items) {
                if (status.equals(payment.path("status").asText())) {
                    String id = payment.path("id").asText(null);
                    if (id != null) {
                        return Mono.just(id);
                    }
                }
            }
            return Mono.empty();
        });
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
