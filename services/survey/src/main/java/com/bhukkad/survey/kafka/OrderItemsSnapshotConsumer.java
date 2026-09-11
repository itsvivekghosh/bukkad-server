package com.bhukkad.survey.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.survey.repository.TrendingDishRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Materializes the ANALYTICS-owned {@code trending_dishes} table from
 * ORDER_ITEMS_SNAPSHOT outbox events (published by the order domain).
 *
 * <p>Listens on {@code order.events.v1} — the base topic the ORDER domain's
 * outbox relay publishes to (its {@code platform-topic}, see
 * {@code OrderEventPublisher.TOPIC}). The previous binding
 * ({@code app.events.external.kafka.platform-topic}) pointed at THIS service's
 * own producer topic ({@code bhukkad.platform.events}), a topic the order
 * domain never writes — the snapshot consumer starved. Matching the
 * Notification/Realtime/Admin consumers, the consumed stream is pinned by
 * constant; dispatch happens on the eventType inside the envelope.</p>
 *
 * <p>Gated on {@code app.events.external.enabled} — inactive when Kafka is
 * off, in which case trending degrades to an empty list (the read path
 * already handles that). Payload shape (from the monolith's
 * OrderItemsSnapshotEvent): orderId, orderNumber, restaurantId,
 * items[{menuItemId, name, quantity}], orderedAt.</p>
 *
 * <p><strong>PERF-2 hardening (AdminCqrsEventConsumer pattern):</strong> the
 * listener is {@code @Transactional} and claims the eventId in a
 * {@code KAFKA_CONSUME}-scoped idempotency record INSIDE the projection
 * transaction — first claim wins, redeliveries are skipped, and a failed
 * upsert rolls the claim back so the DLT replay can retry. No blanket
 * {@code catch}: a malformed envelope or payload is a
 * {@link PoisonEventException} so the platform {@code DefaultErrorHandler}
 * retries and parks the record on {@code order.events.v1.dlt} (V-10).</p>
 */
@Component
@ConditionalOnProperty(name = "app.events.external.enabled", havingValue = "true")
public class OrderItemsSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderItemsSnapshotConsumer.class);

    /** Consumed stream: the ORDER domain publishes the snapshot on its own platform topic. */
    static final String TOPIC_ORDER_EVENTS = "order.events.v1";

    private static final String TYPE_ORDER_ITEMS_SNAPSHOT = "ORDER_ITEMS_SNAPSHOT";
    private static final Duration SNAPSHOT_DEDUPE_TTL = Duration.ofDays(7);

    private final TrendingDishRepository trendingDishRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyRecordRepository idempotencyRecords;

    public OrderItemsSnapshotConsumer(TrendingDishRepository trendingDishRepository,
                                      ObjectMapper objectMapper,
                                      IdempotencyRecordRepository idempotencyRecords) {
        this.trendingDishRepository = trendingDishRepository;
        this.objectMapper = objectMapper;
        this.idempotencyRecords = idempotencyRecords;
    }

    @KafkaListener(
            topics = TOPIC_ORDER_EVENTS,
            groupId = "${app.events.external.kafka.consumer-group:survey-platform-consumer}")
    @Transactional
    public void onPlatformEvent(String payload) {
        // Throws on an unparsable envelope -> DefaultErrorHandler retries -> DLT.
        PlatformEventMessage event = parseEnvelope(payload);
        if (!TYPE_ORDER_ITEMS_SNAPSHOT.equals(event.eventType())) {
            return; // deliberate skip: not our event type
        }
        JsonNode root = readPayload(event);
        // First-write-wins claim INSIDE the projection transaction: a
        // redelivered eventId cannot double-count trending dishes (V-12).
        if (claimEventId(event.eventId()) == 0) {
            log.debug("ORDER_ITEMS_SNAPSHOT_DUPLICATE_SKIPPED | eventId={}", event.eventId());
            return;
        }
        Long restaurantId = root.path("restaurantId").asLong(0L);
        String orderedAt = root.path("orderedAt").asText(null);
        LocalDateTime orderedAtTime = parse(orderedAt);
        for (JsonNode item : root.path("items")) {
            long menuItemId = item.path("menuItemId").asLong(0L);
            String name = item.path("name").asText(null);
            long quantity = item.path("quantity").asLong(1L);
            if (menuItemId <= 0 || name == null || quantity <= 0) {
                // Deliberate per-line skip: a partially broken item line must
                // not poison the whole snapshot's remaining items.
                continue;
            }
            trendingDishRepository.upsert(menuItemId, restaurantId, name, quantity, orderedAtTime);
        }
        log.info("Trending dishes updated | orderId={} | restaurantId={} | eventId={}",
                event.aggregateId(), restaurantId, event.eventId());
    }

    /** First-write-wins claim on (KAFKA_CONSUME, eventId); 0 = already materialized. */
    private int claimEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // No dedup token at all: the claim cannot be honoured — treat as
            // poison rather than double-count on the next replay.
            throw new PoisonEventException("ORDER_ITEMS_SNAPSHOT without eventId");
        }
        return idempotencyRecords.insertIfAbsent(
                eventId,
                IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME.name(),
                null,
                IdempotencyRecord.IdempotencyStatus.COMPLETED.name(),
                null,
                LocalDateTime.now().plus(SNAPSHOT_DEDUPE_TTL));
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
                    "Unparsable ORDER_ITEMS_SNAPSHOT payload: eventId=" + event.eventId(), e);
        }
    }

    private LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }
}
