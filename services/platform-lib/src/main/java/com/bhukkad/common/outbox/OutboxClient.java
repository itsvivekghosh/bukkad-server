package com.bhukkad.common.outbox;

import com.bhukkad.common.event.PlatformEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Write-side outbox client. A service calls {@link #enqueue} inside its own
 * business transaction so the outbox row commits atomically with the domain
 * change; the poller publishes it to Kafka afterwards (plan §6.1).
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxClient {

    public static final String AGGREGATE_DEFAULT = "ORDER";

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public void enqueue(String eventType, Long aggregateId, String payload) {
        enqueue(eventType, AGGREGATE_DEFAULT, aggregateId, payload);
    }

    @Transactional
    public void enqueue(String eventType, String aggregateType, Long aggregateId, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(payload);
        event.setStatus(OutboxEvent.OutboxStatus.PENDING);
        outboxEventRepository.save(event);
        log.debug("OUTBOX_ENQUEUED | type={} | aggregateType={} | aggregateId={}",
                eventType, aggregateType, aggregateId);
    }

    /**
     * Enqueues a full {@link PlatformEventMessage} envelope. The envelope's
     * {@code payload} is stored as the outbox payload; the envelope metadata
     * (eventId, correlationId, traceparent, occurredAt) is embedded as JSON so
     * the poller can reconstruct the Kafka message exactly.
     */
    @Transactional
    public void enqueue(PlatformEventMessage message, Long aggregateId) {
        enqueue(message.eventType(), aggregateTypeFrom(message), aggregateId, message.toJson());
    }

    private String aggregateTypeFrom(PlatformEventMessage message) {
        return message.aggregateId() == null || message.aggregateId().isBlank()
                ? AGGREGATE_DEFAULT : message.aggregateId();
    }

    /** Marker for the instant an event occurred, useful for audits and lag metrics. */
    public Instant now() {
        return Instant.now();
    }
}
