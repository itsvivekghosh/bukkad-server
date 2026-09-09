package com.bhukkad.common.event;

import java.time.Instant;

/**
 * Dispute domain events (order-service owned; refund execution is the payment
 * service's responsibility via {@code DisputeResolved}).
 */
public final class DisputeEvents {
    private DisputeEvents() {
    }

    /**
     * Published when a dispute reaches a resolution that requires money to move
     * (FULL_REFUND / PARTIAL_REFUND). The payment service consumes this to
     * credit the customer's wallet.
     *
     * @param disputeId    the resolved dispute
     * @param orderId      the disputed order
     * @param customerId   the customer to refund
     * @param refundAmount the amount to credit
     * @param resolution   FULL_REFUND or PARTIAL_REFUND
     * @param occurredAt   when the resolution happened
     */
    public record DisputeResolved(Long disputeId, Long orderId, Long customerId,
                                  Double refundAmount, String resolution, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return disputeId;
        }

        @Override
        public String eventType() {
            return "DisputeResolved";
        }

        @Override
        public Object payload() {
            return this;
        }
    }
}