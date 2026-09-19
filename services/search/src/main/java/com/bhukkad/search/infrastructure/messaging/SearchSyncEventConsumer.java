package com.bhukkad.search.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.search.domain.service.impl.SearchSyncProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * ADR-002 event-driven search sync with bulk queue/flush.
 *
 * <p>Consumes the restaurant domain stream ({@code restaurant.events.v1}) and
 * maintains the {@code restaurant_search}/{@code menu_item_search} read tables.
 *
 * <p>Architecture:
 * <ol>
 *   <li>{@code @KafkaListener} enqueues events into a {@link LinkedBlockingQueue}.</li>
 *   <li>{@code @Scheduled} flush drains the queue every 1s and processes events in batches.</li>
 *   <li>Failed events are re-queued for the next flush attempt.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchSyncEventConsumer {

    static final String TOPIC_RESTAURANT_EVENTS = "restaurant.events.v1";
    static final String TOPIC_MENU_EVENTS = "menu.events.v1";
    static final String TYPE_RESTAURANT_UPDATED = "restaurant_updated";
    static final String TYPE_MENU_ITEM_CHANGED = "menu_item_changed";
    static final String TYPE_MENU_ITEM_DELETED = "menu_item_deleted";

    static final String SCOPE_SEARCH_SYNC = "SEARCH_SYNC";
    private static final Duration DEDUPE_TTL = Duration.ofDays(7);
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 500;
    private static final long FLUSH_INTERVAL_MS = 1_000;

    private final SearchSyncProjectionService projectionService;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ObjectMapper objectMapper;

    private final LinkedBlockingQueue<PlatformEventMessage> eventQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    @KafkaListener(id = "search.restaurant-sync",
            topics = {TOPIC_RESTAURANT_EVENTS, TOPIC_MENU_EVENTS},
            groupId = "${app.events.external.kafka.consumer-group:search-sync-consumer}",
            concurrency = "6")
    @Transactional
    public void onRestaurantEvent(String payload) {
        try {
            PlatformEventMessage event = objectMapper.readValue(payload, PlatformEventMessage.class);
            if (!eventQueue.offer(event)) {
                log.warn("SEARCH_SYNC_QUEUE_FULL dropping eventType={} eventId={}", event.eventType(), event.eventId());
            }
        } catch (Exception e) {
            throw new PoisonEventException("Malformed search-sync envelope", e);
        }
    }

    @Scheduled(fixedRate = FLUSH_INTERVAL_MS)
    @Transactional
    public void flushBatch() {
        List<PlatformEventMessage> batch = new ArrayList<>(BATCH_SIZE);
        eventQueue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("SEARCH_SYNC_BATCH_FLUSH_START batchSize={} queueRemaining={}", batch.size(), eventQueue.size());

        List<PlatformEventMessage> failed = new ArrayList<>();
        for (PlatformEventMessage event : batch) {
            try {
                processEvent(event);
            } catch (Exception ex) {
                log.warn("SEARCH_SYNC_EVENT_FAILED eventType={} eventId={} error={}",
                        event.eventType(), event.eventId(), ex.getMessage());
                failed.add(event);
            }
        }

        // Re-queue failed events for the next flush attempt
        for (PlatformEventMessage event : failed) {
            if (!eventQueue.offer(event)) {
                log.error("SEARCH_SYNC_REQUEUE_FAILED queueFull eventType={} eventId={}",
                        event.eventType(), event.eventId());
            }
        }

        if (!failed.isEmpty()) {
            log.warn("SEARCH_SYNC_BATCH_FLUSH_PARTIAL processed={} failed={}", batch.size() - failed.size(), failed.size());
        } else {
            log.debug("SEARCH_SYNC_BATCH_FLUSH_COMPLETE batchSize={}", batch.size());
        }
    }

    void processEvent(PlatformEventMessage event) {
        if (claimEventId(event.eventId()) == 0) {
            log.debug("SEARCH_SYNC_DUPLICATE_SKIPPED eventId={} type={}", event.eventId(), event.eventType());
            return;
        }

        JsonNode data = readPayload(event);
        long id = data.path("id").asLong(0L);
        if (id <= 0) {
            throw new PoisonEventException(event.eventType() + " without a usable id: eventId=" + event.eventId());
        }

        switch (event.eventType()) {
            case TYPE_RESTAURANT_UPDATED -> projectionService.upsertRestaurant(data.path("id").asLong(0L), data);
            case TYPE_MENU_ITEM_CHANGED -> projectionService.upsertMenuItem(data.path("id").asLong(0L), data);
            case TYPE_MENU_ITEM_DELETED -> projectionService.deleteMenuItem(data.path("id").asLong(0L), data);
            default -> log.debug("SEARCH_SYNC_EVENT_IGNORED type={}", event.eventType());
        }

        log.info("SEARCH_SYNC_PROJECTED type={} id={} eventId={}", event.eventType(), id, event.eventId());
    }

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
}
