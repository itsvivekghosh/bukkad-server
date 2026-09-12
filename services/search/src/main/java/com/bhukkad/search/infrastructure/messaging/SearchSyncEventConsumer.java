package com.bhukkad.search.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.search.domain.service.impl.SearchSyncProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * ADR-002 event-driven search sync: consumes the restaurant domain stream
 * ({@code restaurant.events.v1}) and maintains the
 * {@code restaurant_search}/{@code menu_item_search} read tables.
 *
 * <p>Handled event types (produced by restaurant's {@code MenuEventsPublisher}
 * on the mutation points this batch owns):</p>
 * <ul>
 *   <li>{@code restaurant_updated} → upsert the restaurant document;</li>
 *   <li>{@code menu_item_changed} → upsert the menu-item document;</li>
 *   <li>{@code menu_item_deleted} → delete the menu-item document
 *       (delete propagation — no orphan hits).</li>
 * </ul>
 *
 * <p><strong>Idempotency</strong> (same story as {@code AdminCqrsEventConsumer}):
 * each event's {@code eventId} is claimed in an {@code idempotency_records}
 * row (scope {@code SEARCH_SYNC}) INSIDE the same transaction as the
 * projection update — first claim wins, re-deliveries are skipped, and a
 * replay after a crash cannot apply a stale document twice.</p>
 *
 * <p><strong>No silent loss</strong> (audit V-10): the listener does not
 * blanket-catch; parse/validation failures throw {@link PoisonEventException}
 * so the platform {@code DefaultErrorHandler} retries and parks the record on
 * {@code restaurant.events.v1.dlt}. Uninteresting event types are explicitly
 * skipped.</p>
 *
 * <p>The Kafka consumer infrastructure comes from platform-lib's
 * {@code KafkaPlatformConfig}, which only activates when
 * {@code app.events.external.enabled=true} AND {@code type=kafka}; with the
 * gate off the annotation is inert and the periodic reconciliation sweep is
 * the only sync path (same degraded-read story as before this batch).</p>
 */
@Component
public class SearchSyncEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SearchSyncEventConsumer.class);

    static final String TOPIC_RESTAURANT_EVENTS = "restaurant.events.v1";
    static final String TYPE_RESTAURANT_UPDATED = "restaurant_updated";
    static final String TYPE_MENU_ITEM_CHANGED = "menu_item_changed";
    static final String TYPE_MENU_ITEM_DELETED = "menu_item_deleted";

    /**
     * Dedupe scope for the search projections. Deliberately stored as the raw
     * string {@code SEARCH_SYNC} (the {@code idempotency_records.scope} column
     * is VARCHAR and the unique key is {@code (scope, idempotency_key)}):
     * platform-lib owns the {@code IdempotencyScope} enum and is frozen for
     * this batch, and no code path reads these rows back through the JPA enum
     * mapping (the cleanup sweep deletes natively by expiry).
     */
    static final String SCOPE_SEARCH_SYNC = "SEARCH_SYNC";

    private static final Duration DEDUPE_TTL = Duration.ofDays(7);

    private final SearchSyncProjectionService projectionService;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ObjectMapper objectMapper;

    public SearchSyncEventConsumer(SearchSyncProjectionService projectionService,
                                   IdempotencyRecordRepository idempotencyRecords,
                                   ObjectMapper objectMapper) {
        this.projectionService = projectionService;
        this.idempotencyRecords = idempotencyRecords;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "search.restaurant-sync",
            topics = TOPIC_RESTAURANT_EVENTS,
            groupId = "${app.events.external.kafka.consumer-group:search-sync-consumer}")
    @Transactional
    public void onRestaurantEvent(String payload) {
        PlatformEventMessage event;
        try {
            event = objectMapper.readValue(payload, PlatformEventMessage.class);
        } catch (Exception e) {
            throw new PoisonEventException("Malformed search-sync envelope", e);
        }
        switch (event.eventType()) {
            case TYPE_RESTAURANT_UPDATED -> apply(event,
                    data -> projectionService.upsertRestaurant(data.path("id").asLong(0L), data));
            case TYPE_MENU_ITEM_CHANGED -> apply(event,
                    data -> projectionService.upsertMenuItem(data.path("id").asLong(0L), data));
            case TYPE_MENU_ITEM_DELETED -> apply(event,
                    data -> projectionService.deleteMenuItem(data.path("id").asLong(0L), data));
            default -> log.debug("SEARCH_SYNC_EVENT_IGNORED | type={}", event.eventType());
        }
    }

    private void apply(PlatformEventMessage event, ProjectionUpdate update) {
        JsonNode data = readPayload(event);
        long id = data.path("id").asLong(0L);
        if (id <= 0) {
            // Validate BEFORE burning the dedupe row: a poison record parked on
            // the DLT must stay replayable once fixed (V-10 pairing).
            throw new PoisonEventException(
                    event.eventType() + " without a usable id: eventId=" + event.eventId());
        }
        if (claimEventId(event.eventId()) == 0) {
            log.debug("SEARCH_SYNC_DUPLICATE_SKIPPED | eventId={} | type={}", event.eventId(), event.eventType());
            return;
        }
        update.apply(data);
        log.info("SEARCH_SYNC_PROJECTED | type={} | id={} | eventId={}",
                event.eventType(), id, event.eventId());
    }

    /** First-write-wins claim on (SEARCH_SYNC, eventId); 0 = already projected. */
    private int claimEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new PoisonEventException("Search-sync event without eventId");
        }
        return idempotencyRecords.insertIfAbsent(
                eventId,
                SCOPE_SEARCH_SYNC,
                null,
                "COMPLETED",
                null,
                LocalDateTime.now().plus(DEDUPE_TTL));
    }

    private JsonNode readPayload(PlatformEventMessage event) {
        try {
            return objectMapper.readTree(event.payload());
        } catch (Exception e) {
            throw new PoisonEventException("Malformed search-sync payload: " + event.eventId(), e);
        }
    }

    /** One projection update per event type (lambda target to share claim+log plumbing). */
    @FunctionalInterface
    interface ProjectionUpdate {
        void apply(JsonNode payload);
    }
}
