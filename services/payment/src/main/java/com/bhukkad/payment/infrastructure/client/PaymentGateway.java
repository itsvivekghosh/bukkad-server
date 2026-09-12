package com.bhukkad.payment.infrastructure.client;

import java.math.BigDecimal;

/**
 * Payment-gateway abstraction (port of monolith {@code PaymentGateway}).
 * Services code against this interface; the strategy picks the implementation
 * (simulated, Razorpay, etc.) by configuration.
 */
public interface PaymentGateway {

    GatewayResult authorize(Long paymentId, Long customerId, BigDecimal amount, String currency);

    GatewayResult refund(Long paymentId, BigDecimal amount);

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