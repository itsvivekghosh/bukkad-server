package com.bhukkad.common.event;

import java.time.Instant;

/**
 * Order domain events.
 */
public final class OrderEvents {
    private OrderEvents() {
    }

    public record OrderCreated(Long orderId, Long customerId, Long restaurantId, Double totalAmount, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return orderId;
        }

        @Override
        public String eventType() {
            return "OrderCreated";
        }

        @Override
        public Object payload() {
            return this;
        }
    }

    public record OrderStatusUpdated(Long orderId, String status, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return orderId;
        }

        @Override
        public String eventType() {
            return "OrderStatusUpdated";
        }

        @Override
        public Object payload() {
            return this;
        }
    }

    public record OrderCancelled(Long orderId, String reason, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return orderId;
        }

        @Override
        public String eventType() {
            return "OrderCancelled";
        }

        @Override
        public Object payload() {
            return this;
        }
    }
}
