package com.bhukkad.event;

/**
 * Outbox event payload for a processed Razorpay webhook.
 *
 * <p>Previously {@code PAYMENT_WEBHOOK_RECEIVED} had no case in the outbox
 * processor switch and dead-lettered on every payment webhook. The money path
 * is applied transactionally inside {@code PaymentServiceImpl#completeWebhookPayment},
 * so this event carries no side effects of its own — it exists so the durable
 * fact that a webhook was received and handled is observable and auditable
 * instead of being poisoned into the dead-letter queue.
 *
 * @param eventId          the Razorpay webhook event id
 * @param gatewayOrderId   the Razorpay order id
 * @param gatewayPaymentId the Razorpay payment id
 */
public record PaymentWebhookReceivedEvent(String eventId, String gatewayOrderId, String gatewayPaymentId) {
}
