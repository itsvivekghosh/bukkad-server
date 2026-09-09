package com.bhukkad.payment.gateway;

import java.math.BigDecimal;
import java.util.Random;

/**
 * Deterministic simulated gateway for development and testing (port of
 * monolith {@code SimulatedPaymentGateway}). Always succeeds for amounts ≤
 * 10,000 INR; larger amounts fail with a "declined" message.
 */
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final BigDecimal MAX_SIMULATED = new BigDecimal("10000.00");
    private final Random random = new Random(42);

    @Override
    public GatewayResult authorize(Long paymentId, Long customerId, BigDecimal amount, String currency) {
        if (amount.compareTo(MAX_SIMULATED) > 0) {
            return GatewayResult.failed("Simulated gateway declined: amount exceeds limit");
        }
        return GatewayResult.ok("SIM-PROV-" + random.nextInt(999999));
    }

    @Override
    public GatewayResult refund(Long paymentId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return GatewayResult.failed("Refund amount must be positive");
        }
        return GatewayResult.ok("SIM-REF-" + random.nextInt(999999));
    }
}