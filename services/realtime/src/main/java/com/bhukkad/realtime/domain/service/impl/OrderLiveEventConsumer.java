package com.bhukkad.realtime.domain.service.impl;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.realtime.domain.event.LiveUpdateEvent;
import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.domain.service.OrderLiveRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Strangler bridge from the platform order event stream
 * ({@code order.events.v1}) into the realtime live-update pipeline.
 *
 * <p>This is the {@code @KafkaListener}-based consumer that replaces the
 * monolith's {@code OrderLiveRedisSubscriber} +
 * {@code OrderLiveUpdateBroadcaster} pair. Each order domain event is mapped to
 * an {@link OrderLiveUpdate} (preserving the kitchen / order / rider channel
 * semantics of {@code OrderLiveTopics}) wrapped in a {@link LiveUpdateEvent}
 * envelope and handed to {@link OrderLiveRelay#relay(LiveUpdateEvent)} which
 * assigns the monotonic event id, records replay state and publishes to the
 * per-stream Redis relay channels.</p>
 *
 * <p>The Kafka consumer infrastructure ({@code @EnableKafka},
 * {@code kafkaListenerContainerFactory}, {@code ConsumerFactory}) is wired by
 * platform-lib's {@code KafkaPlatformConfig} and only activates when
 * {@code app.events.external.enabled=true}; when inactive the
 * {@code @KafkaListener} annotation is a no-op, matching the
 * {@code AdminCqrsEventConsumer} / {@code NotificationEventConsumer} precedent.</p>
 *
 * <p><strong>PERF-2 hardening (mirrors NotificationEventConsumer):</strong></p>
 * <ul>
 *   <li><em>V-10</em> — no blanket {@code catch (Exception){log}}: a malformed
 *       envelope or payload is a {@link PoisonEventException} so the container's
 *       {@code DefaultErrorHandler} retries and parks the record on
 *       {@code order.events.v1.dlt} instead of committing it into oblivion.</li>
 *   <li><em>Dedupe</em> — the eventId is claimed in a
 *       {@code KAFKA_CONSUME}-scoped idempotency record BEFORE relaying
 *       (at-least-once delivery must not fan out duplicate SSE updates on
 *       replay); a relay failure releases the claim and rethrows so a DLT
 *       replay can re-relay.</li>
 * </ul>
 *
 * @see com.bhukkad.common.kafka.KafkaPlatformConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderLiveEventConsumer {

    static final String TOPIC_ORDER_EVENTS = "order.events.v1";
    private static final String TYPE_ORDER_CREATED = "OrderCreated";
    private static final String TYPE_ORDER_STATUS_CHANGED = "OrderStatusChanged";
    private static final String STATUS_PLACED = "PLACED";
    private static final Duration CONSUME_DEDUPE_TTL = Duration.ofHours(48);

    private final OrderLiveRelay relay;
    private final ObjectMapper objectMapper;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(id = "realtime.order-live", topics = TOPIC_ORDER_EVENTS,
            groupId = "${app.events.external.kafka.consumer-group:realtime-platform-consumer}")
    public void onOrderEvent(String payload) {
        // Throws on an unparsable envelope -> DefaultErrorHandler retries -> DLT.
        PlatformEventMessage event = parseEnvelope(payload);
        if (!TYPE_ORDER_CREATED.equals(event.eventType())
                && !TYPE_ORDER_STATUS_CHANGED.equals(event.eventType())) {
            return; // deliberate skip: not our event type
        }
        // Validate BEFORE burning the dedupe row: a poison record parked on the
        // DLT must stay replayable once fixed (V-10 pairing).
        LiveUpdateEvent live = toLiveUpdateEvent(event);

        // eventId claim committed BEFORE relaying (PERF-2/P-07): only the first
        // delivery of an eventId may fan out to the SSE streams.
        if (claimEventId(event.eventId()) == 0) {
            log.debug("LIVE_EVENT_DUPLICATE_SKIPPED | eventId={}", event.eventId());
            return;
        }
        try {
            relay.relay(live);
        } catch (RuntimeException e) {
            // Relay failed: undo the claim so a DLT replay can re-relay, then
            // rethrow -> DefaultErrorHandler -> DLT. Never swallow (V-10).
            releaseClaim(event.eventId());
            throw e;
        }
        log.info("LIVE_EVENT_CONSUMED | type={} | aggregateId={} | orderId={}",
                event.eventType(), event.aggregateId(), orderIdOf(live));
    }

    private LiveUpdateEvent toLiveUpdateEvent(PlatformEventMessage event) {
        JsonNode data = readPayload(event);
        LocalDateTime occurredAt = toLocalDateTime(event.occurredAt());
        if (TYPE_ORDER_STATUS_CHANGED.equals(event.eventType())) {
            OrderLiveUpdate update = OrderLiveUpdate.builder()
                    .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED)
                    .orderId(asLong(data, "orderId"))
                    .status(text(data, "status"))
                    .changedAt(occurredAt)
                    .build();
            return new LiveUpdateEvent(event.eventType(), update);
        }
        // OrderCreated — only the two tracked types reach this point.
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.ORDER_CREATED)
                .orderId(asLong(data, "orderId"))
                .customerId(asLong(data, "customerId"))
                .restaurantId(asLong(data, "restaurantId"))
                .status(STATUS_PLACED)
                .changedAt(occurredAt)
                .build();
        return new LiveUpdateEvent(event.eventType(), update);
    }

    private PlatformEventMessage parseEnvelope(String payload) {
        try {
            return objectMapper.readValue(payload, PlatformEventMessage.class);
        } catch (Exception e) {
            throw new PoisonEventException("Malformed event envelope", e);
        }
    }

    private JsonNode readPayload(PlatformEventMessage event) {
        try {
            return objectMapper.readTree(event.payload());
        } catch (Exception e) {
            throw new PoisonEventException(
                    "Unparsable " + event.eventType() + " payload: eventId=" + event.eventId(), e);
        }
    }

    /** First-write-wins claim on (KAFKA_CONSUME, eventId); 0 = already relayed. */
    private int claimEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // No dedup token at all: the claim cannot be honoured — treat as
            // poison rather than double-fan-out on the next replay.
            throw new PoisonEventException("Order event without eventId");
        }
        Integer claimed = transactionTemplate.execute(status -> idempotencyRecords.insertIfAbsent(
                eventId,
                IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME.name(),
                null,
                IdempotencyRecord.IdempotencyStatus.COMPLETED.name(),
                null,
                LocalDateTime.now().plus(CONSUME_DEDUPE_TTL)));
        return claimed == null ? 0 : claimed;
    }

    private void releaseClaim(String eventId) {
        try {
            transactionTemplate.executeWithoutResult(status -> idempotencyRecords
                    .deleteByScopeAndIdempotencyKey(
                            IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME, eventId));
        } catch (Exception e) {
            log.warn("LIVE_EVENT_CLAIM_RELEASE_FAILED | eventId={} | error={}",
                    eventId, e.getMessage());
        }
    }

    private static Long orderIdOf(LiveUpdateEvent live) {
        Object payload = live.getPayload();
        return payload instanceof OrderLiveUpdate update ? update.getOrderId() : null;
    }

    private static Long asLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
