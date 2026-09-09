package com.bhukkad.common.event;

/**
 * Publishes platform events to the outbox or message broker.
 *
 * <p>Implementations may write to the outbox table (for reliable delivery),
 * publish to Kafka, or use another transport. The contract is intentionally
 * minimal: services publish events; the infrastructure handles delivery.</p>
 */
public interface PlatformEventPublisher {
    void publish(PlatformEvent event);
}
