package com.bhukkad.common.event;

import java.time.Instant;

/**
 * Base interface for all platform events exchanged between services.
 *
 * <p>Each event carries an aggregate ID, event type, timestamp, and payload.
 * Implementations are immutable records for type-safety and pattern matching.</p>
 */
public interface PlatformEvent {
    Long aggregateId();
    String eventType();
    Instant occurredAt();
    Object payload();
}
