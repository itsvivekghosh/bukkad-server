package com.bhukkad.order.infrastructure.client;

/**
 * Body of {@code POST /api/v1/internal/payments/charge} (order saga → payment
 * service, audit batch A). {@code amount} is the string decimal form required
 * by the payment contract; {@code reference} (e.g. {@code ORDER-<id>}) is the
 * server-side idempotency key that makes saga retries safe.
 */
public record ChargeRequest(Long orderId, Long customerId, String amount,
                            String paymentMethod, String reference) {
}
