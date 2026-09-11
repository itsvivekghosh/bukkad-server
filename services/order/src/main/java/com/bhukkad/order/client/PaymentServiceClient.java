package com.bhukkad.order.client;

import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import com.bhukkad.order.client.dto.ChargeRequest;
import com.bhukkad.order.client.dto.ChargeResponse;
import com.bhukkad.order.client.dto.RefundResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Reactive client for the Payment service's internal charge surface, used by
 * the order-creation saga CHARGE_PAYMENT step (audit batch A).
 *
 * <p>Mirrors {@link RestaurantClient} on the platform WebClient factory
 * (audit G-13/P-05): shared bounded pool, platform timeouts, retry
 * (3 attempts, 1s backoff on transient idempotent failures), the
 * {@code payment} circuit breaker and metrics; the service base URL is
 * applied on the copied builder. The charge carries a stable
 * {@code reference} (e.g. {@code ORDER-<id>}) so retries are idempotent
 * server-side.</p>
 *
 * <p>Server contract (owned by the payment service):
 * {@code POST /api/v1/internal/payments/charge} with body
 * {@code {"orderId":1,"customerId":2,"amount":"100.00","paymentMethod":"WALLET","reference":"ORDER-1"}}
 * → 200 {@code {"paymentId":5,"status":"CHARGED"}}; 202/4xx mean the step
 * failed. Compensation: {@code POST /api/v1/internal/payments/{paymentId}/refund}
 * body {@code {"reason":"saga-compensation"}} → 200 {@code {"status":"REFUNDED"}}.
 * Both calls authenticate with the mesh {@code X-Service-Token} header when a
 * service token is available.</p>
 */
@Component
public class PaymentServiceClient {

    static final String CHARGE_STATUS = "CHARGED";
    static final String REFUND_STATUS = "REFUNDED";

    private final WebClient webClient;

    public PaymentServiceClient(@Value("${app.services.payment.url}") String baseUrl) {
        this(baseUrl, (org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentServiceClient(@Value("${app.services.payment.url}") String baseUrl,
                                org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider) {
        this.webClient = PlatformWebClientBuilderFactory
                .forTarget("payment",
                        meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable())
                .build()
                .mutate()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Charges {@code amount} for a placed order. Completes with the payment
     * receipt ({@code paymentId} + {@code CHARGED} status) only when the
     * payment service confirms the charge; anything else — non-2xx, a body
     * without a payment id, timeouts — is an error signal so the saga step
     * fails and the compensation chain runs.
     */
    public Mono<ChargeResponse> charge(Long orderId, Long customerId, BigDecimal amount,
                                       String paymentMethod, String reference, String serviceToken) {
        ChargeRequest body = new ChargeRequest(orderId, customerId, amount.toPlainString(), paymentMethod, reference);
        return webClient.post()
                .uri("/api/v1/internal/payments/charge")
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyServiceToken(headers, serviceToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ChargeResponse.class)
                .filter(response -> response.paymentId() != null && CHARGE_STATUS.equals(response.status()))
                // Bounds the WHOLE retry chain, not one attempt: the filters
                // already cap each attempt at 5s and the RetryFilter may make
                // 3 attempts with 1s backoffs (~17s worst case). A 5s outer
                // timeout killed legitimate retries mid-flight under load
                // (TimeoutException in 'filter' — seen in parallel builds).
                .timeout(Duration.ofSeconds(20));
    }

    /**
     * Refunds a previously charged payment (saga compensation of
     * CHARGE_PAYMENT). Completes with the refund receipt on 200 with
     * {@code status=REFUNDED}; errors propagate — a failed compensation must
     * stay visible so the saga is marked FAILED rather than silently stuck.
     */
    public Mono<RefundResponse> refund(Long paymentId, String reason, String serviceToken) {
        return webClient.post()
                .uri("/api/v1/internal/payments/{paymentId}/refund", paymentId)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyServiceToken(headers, serviceToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reason", reason))
                .retrieve()
                .bodyToMono(RefundResponse.class)
                .filter(response -> REFUND_STATUS.equals(response.status()))
                .timeout(Duration.ofSeconds(20));
    }

    private static void applyServiceToken(org.springframework.http.HttpHeaders headers, String serviceToken) {
        if (serviceToken != null && !serviceToken.isBlank()) {
            headers.set("X-Service-Token", serviceToken);
        }
    }
}
