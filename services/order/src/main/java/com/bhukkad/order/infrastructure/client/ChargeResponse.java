package com.bhukkad.order.infrastructure.client;

/**
 * Receipt of a completed charge ({@code paymentId} + terminal status, e.g.
 * {@code CHARGED}). The payment id feeds the saga's CHARGE_PAYMENT
 * compensation (refund); an empty/unknown response fails the step.
 */
public record ChargeResponse(Long paymentId, String status) {
}
