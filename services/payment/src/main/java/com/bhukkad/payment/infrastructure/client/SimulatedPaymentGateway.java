package com.bhukkad.payment.infrastructure.client;

import java.math.BigDecimal;
import java.util.Random;
import reactor.core.publisher.Mono;

/**
 * Deterministic simulated gateway for development and testing (port of
 * monolith {@code SimulatedPaymentGateway}). Always succeeds for amounts ≤
 * 10,000 INR; larger amounts fail with a "declined" message.
 *
 * <p>Returns {@link Mono} to match the {@link PaymentGateway} contract; the
 * simulated result is immediate so we wrap it in {@link Mono#just}.</p>
 */
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final BigDecimal MAX_SIMULATED = new BigDecimal("10000.00");
    private final Random random = new Random(42);

    @Override
    public Mono<GatewayResult> authorize(Long paymentId, Long customerId, BigDecimal amount, String currency) {
        if (amount.compareTo(MAX_SIMULATED) > 0) {
            return Mono.just(GatewayResult.failed("Simulated gateway declined: amount exceeds limit"));
        }
        return Mono.just(GatewayResult.ok("SIM-PROV-" + random.nextInt(999999)));
    }

    @Override
    public Mono<GatewayResult> refund(Long paymentId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return Mono.just(GatewayResult.failed("Refund amount must be positive"));
        }
        return Mono.just(GatewayResult.ok("SIM-REF-" + random.nextInt(999999)));
    }
}