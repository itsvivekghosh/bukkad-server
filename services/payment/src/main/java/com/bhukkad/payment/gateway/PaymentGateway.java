package com.bhukkad.payment.gateway;

import java.math.BigDecimal;

/**
 * Payment-gateway abstraction (port of monolith {@code PaymentGateway}).
 * Services code against this interface; the strategy picks the implementation
 * (simulated, Razorpay, etc.) by configuration.
 */
public interface PaymentGateway {

    GatewayResult authorize(Long paymentId, Long customerId, BigDecimal amount, String currency);

    GatewayResult refund(Long paymentId, BigDecimal amount);

    record GatewayResult(boolean success, String providerRef, String message) {
        public static GatewayResult ok(String providerRef) {
            return new GatewayResult(true, providerRef, "OK");
        }

        public static GatewayResult failed(String message) {
            return new GatewayResult(false, null, message);
        }
    }
}