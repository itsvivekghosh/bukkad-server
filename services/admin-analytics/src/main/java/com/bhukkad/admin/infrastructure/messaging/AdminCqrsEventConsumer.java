package com.bhukkad.admin.infrastructure.messaging;

import com.bhukkad.admin.domain.repository.RestaurantOrderStatRepository;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * CQRS read-model projection (P7): consumes order events and maintains the
 * {@code restaurant_order_stats} aggregate so admin dashboards read a
 * pre-computed metric instead of querying the order service.
 *
 * <p><strong>Honest idempotency story (the previous javadoc lied — audit
 * V-12/RC-F: "re-delivered events only re-increment once per unique order id"
 * was aspirational; no dedup existed).</strong> Each event's {@code eventId}
 * is claimed in a {@code KAFKA_CONSUME}/{@code ADMIN_PROJECTION}
 * idempotency record ({@code ADMIN_PROJECTION} scope) INSIDE the same
 * transaction as the projection update — first claim wins, redeliveries are
 * skipped, and a replay of the log after a crash cannot double count.</p>
 *
 * <p><strong>Atomic projection:</strong> counters move via a native
 * {@code INSERT … ON CONFLICT (restaurant_id) DO UPDATE} (count+1, revenue+=)
 * — the old findById→+1→save sequence lost updates under concurrency
 * (RC-A).</p>
 *
 * <p><strong>No silent loss (audit V-10):</strong> the listener no longer
 * wraps its body in a blanket {@code catch (Exception){log}}. A parse failure
 * or DB error propagates so the platform {@code DefaultErrorHandler} retries
 * and parks the poison record on {@code order.events.v1.dlt}; only
 * uninteresting event types are deliberately (and explicitly) skipped.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminCqrsEventConsumer {

    private static final String TOPIC_ORDER_EVENTS = "order.events.v1";
    private static final String TYPE_ORDER_CREATED = "OrderCreated";
    private static final Duration PROJECTION_DEDUPE_TTL = Duration.ofDays(7);

    private final RestaurantOrderStatRepository statRepository;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ObjectMapper objectMapper;

    @KafkaListener(id = "admin.cqrs", topics = TOPIC_ORDER_EVENTS, groupId = "${app.events.external.kafka.consumer-group}")
    @Transactional
    public void onOrderEvent(String payload) {
        // Throws on unparsable envelope -> DefaultErrorHandler retries -> DLT.
        PlatformEventMessage event = parse(payload);
        if (!TYPE_ORDER_CREATED.equals(event.eventType())) {
            return; // deliberate skip: not our event type
        }
        JsonNode data = readPayload(event);
        long restaurantId = data.path("restaurantId").asLong(0L);
        if (restaurantId <= 0) {
            // Validate BEFORE burning the dedupe row: a poison record parked on
            // the DLT must stay replayable once fixed (V-10 pairing).
            throw new PoisonEventException("OrderCreated without a usable restaurantId: eventId="
                    + event.eventId());
        }
        if (claimEventId(event.eventId()) == 0) {
            log.debug("ADMIN_CQRS_DUPLICATE_SKIPPED | eventId={}", event.eventId());
            return;
        }
        BigDecimal total = data.has("totalAmount")
                ? data.get("totalAmount").decimalValue()
                : BigDecimal.ZERO;
        statRepository.upsertIncrement(restaurantId, total);
        log.info("ADMIN_CQRS_PROJECTED | restaurantId={} | orderId={} | eventId={} | total={}",
                restaurantId, event.aggregateId(), event.eventId(), total);
    }

    /** First-write-wins claim on (ADMIN_PROJECTION, eventId); 0 = already projected. */
    private int claimEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // No dedup token at all: the claim cannot be honoured — treat as
            // poison rather than double-count on the next replay.
            throw new PoisonEventException("OrderCreated without eventId");
        }
        return idempotencyRecords.insertIfAbsent(
                eventId,
                IdempotencyRecord.IdempotencyScope.ADMIN_PROJECTION.name(),
                null,
                IdempotencyRecord.IdempotencyStatus.COMPLETED.name(),
                null,
                LocalDateTime.now().plus(PROJECTION_DEDUPE_TTL));
    }

    private PlatformEventMessage parse(String payload) {
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
            throw new PoisonEventException("Malformed event payload: " + event.eventId(), e);
        }
    }
}
