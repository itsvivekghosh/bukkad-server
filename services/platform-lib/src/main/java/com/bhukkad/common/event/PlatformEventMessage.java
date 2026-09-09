package com.bhukkad.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-service event envelope (architecture-microservices-postgresql.md §6.3).
 *
 * <p>Extends the monolith's {@code PlatformEventMessage} with the fields the
 * per-service topic catalog needs: {@code eventId}, {@code schemaVersion},
 * {@code occurredAt}, {@code correlationId} and the W3C {@code traceparent}
 * (for distributed tracing across Kafka). The {@code payload} is always a JSON
 * string whose structure is versioned by {@code schemaVersion}.</p>
 *
 * <p>This class is deliberately dependency-free (records + Jackson only) so it
 * can be used by producers, consumers, the outbox and admin read-model
 * consumers without pulling in web or persistence concerns.</p>
 *
 * @param eventId       globally unique event id (UUID)
 * @param eventType     canonical event type, e.g. {@code OrderCreated}
 * @param schemaVersion version of the payload contract (starts at 1)
 * @param occurredAt    when the event happened (UTC instant)
 * @param aggregateId   id of the business aggregate the event refers to
 * @param correlationId end-to-end correlation id (may equal {@code eventId})
 * @param traceparent   W3C traceparent header value, propagated across services
 * @param payload       JSON payload string
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformEventMessage(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String correlationId,
        String traceparent,
        String payload
) {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    public PlatformEventMessage {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }

    /**
     * Builds an envelope with generated event id, schema version 1 and the
     * event id as the correlation id — the common "fire and forget" path.
     */
    public static PlatformEventMessage of(String eventType, String aggregateId, String payload) {
        String eventId = UUID.randomUUID().toString();
        return new PlatformEventMessage(
                eventId, eventType, 1, Instant.now(), aggregateId, eventId, null, payload);
    }

    /** Same as {@link #of(String, String, String)} but with an explicit correlation id. */
    public static PlatformEventMessage of(String eventType, String aggregateId,
                                          String correlationId, String payload) {
        return new PlatformEventMessage(
                UUID.randomUUID().toString(), eventType, 1, Instant.now(),
                aggregateId, correlationId, null, payload);
    }

    public static PlatformEventMessage fromJson(String json) {
        try {
            return MAPPER.readValue(json, PlatformEventMessage.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PlatformEventMessage JSON", e);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize PlatformEventMessage", e);
        }
    }
}
