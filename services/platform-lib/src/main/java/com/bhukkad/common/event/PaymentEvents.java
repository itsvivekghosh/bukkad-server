package com.bhukkad.common.event;

import java.time.Instant;

/**
 * Payment domain events.
 */
public final class PaymentEvents {
    private PaymentEvents() {
    }

    public record PaymentCompleted(Long paymentId, Long orderId, Double amount, String paymentMethod, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return orderId;
        }

        @Override
        public String eventType() {
            return "PaymentCompleted";
        }

        @Override
        public Object payload() {
            return this;
        }
    }

    public record PaymentFailed(Long paymentId, Long orderId, String failureReason, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return orderId;
        }

        @Override
        public String eventType() {
            return "PaymentFailed";
        }

        @Override
        public Object payload() {
            return this;
        }
    }
}
