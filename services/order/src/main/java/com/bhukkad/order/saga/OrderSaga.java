package com.bhukkad.order.saga;

import com.bhukkad.common.event.PlatformEvent;
import com.bhukkad.common.event.PlatformEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Order saga coordinator.
 *
 * <p>Demonstrates the data consistency strategy for the order service:
 * local transaction + event publishing + compensation handling.
 *
 * <p>The saga pattern ensures eventual consistency across services without
 * distributed transactions. Each step is a local transaction; events drive
 * the next step; compensations undo partial progress on failure.</p>
 *
 * <p>This is a structural example showing where saga logic lives once the
 * order entity/repository are extracted from the monolith.</p>
 */
@Component
public class OrderSaga {

    private final PlatformEventPublisher eventPublisher;

    public OrderSaga(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Step 1: Create order and publish OrderCreated event.
     *
     * <p>This is the saga initiation step. The order is saved locally, then
     * an event is published to trigger downstream services (payment, delivery,
     * notification).</p>
     */
    public void createOrder(Long orderId, Long customerId, Long restaurantId, Double totalAmount) {
        // In the extracted service this would be:
        // order.setStatus(PLACED);
        // orderRepository.save(order);

        PlatformEvent event = new com.bhukkad.common.event.OrderEvents.OrderCreated(
                orderId,
                customerId,
                restaurantId,
                totalAmount,
                java.time.Instant.now()
        );
        eventPublisher.publish(event);
    }

    /**
     * Step 2: Confirm order after payment is completed.
     */
    public void confirmOrder(Long orderId) {
        // order.setStatus(CONFIRMED);
        // orderRepository.save(order);

        PlatformEvent event = new com.bhukkad.common.event.OrderEvents.OrderStatusUpdated(
                orderId,
                "CONFIRMED",
                java.time.Instant.now()
        );
        eventPublisher.publish(event);
    }

    /**
     * Compensation: Cancel order after payment failure or timeout.
     */
    public void cancelOrder(Long orderId, String reason) {
        // order.setStatus(CANCELLED);
        // orderRepository.save(order);

        PlatformEvent event = new com.bhukkad.common.event.OrderEvents.OrderCancelled(
                orderId,
                reason,
                java.time.Instant.now()
        );
        eventPublisher.publish(event);
    }
}
