package com.bhukkad.common.event;

import java.time.Instant;

/**
 * Canonical platform event envelope shared by every service. Produced via the
 * outbox and forwarded to Redpanda/Kafka by the owning service; consumed by
 * other services and the gateway. {@code traceparent} is propagated in the
 * Kafka headers for end-to-end tracing (Phase 4).
 */
public record PlatformEvent(
        String eventType,
        String aggregateType,
        String aggregateId,
        String payload,
        Instant publishedAt
) {
}
