package com.bhukkad.payment.infrastructure.client;

import java.math.BigDecimal;
import reactor.core.publisher.Mono;

/**
 * Payment-gateway abstraction (port of monolith {@code PaymentGateway}).
 * Services code against this interface; the strategy picks the implementation
 * (simulated, Razorpay, etc.) by configuration.
 *
 * <p>All methods return {@link Mono} so the gateway adapter itself is fully
 * non-blocking. Callers at the service/controller boundary decide whether to
 * {@code block()} (Servlet threads) or stay reactive (WebFlux pipelines).</p>
 */
public interface PaymentGateway {

    Mono<GatewayResult> authorize(Long paymentId, Long customerId, BigDecimal amount, String currency);

    Mono<GatewayResult> refund(Long paymentId, BigDecimal amount);

    record GatewayResult(boolean success, String providerRef, String message, String gatewayOrderId) {

        /** Back-compatible 3-field construction (no PSP order reference). */
        public GatewayResult(boolean success, String providerRef, String message) {
            this(success, providerRef, message, null);
        }

        public static GatewayResult ok(String providerRef) {
            return new GatewayResult(true, providerRef, "OK");
        }

        /** Successful charge carrying both the PSP payment and PSP order references. */
        public static GatewayResult ok(String providerRef, String gatewayOrderId) {
            return new GatewayResult(true, providerRef, "OK", gatewayOrderId);
        }

        public static GatewayResult failed(String message) {
            return new GatewayResult(false, null, message);
        }
    }
}