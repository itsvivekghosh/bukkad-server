package com.bhukkad.notificationservice.event;

import com.bhukkad.common.event.PlatformEvent;
import com.bhukkad.notificationservice.dispatch.NotificationDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes {@code NOTIFICATION_REQUESTED} platform events from the shared
 * Kafka topic and dispatches them via the appropriate channel sender.
 *
 * <p>Idempotency: a fixed-size in-memory set of recently-seen eventIds
 * prevents duplicate dispatch within a rolling window. A production deployment
 * would replace this with a persistent deduplication table (e.g.
 * {@code notification_deduplication} with a unique constraint on
 * {@code event_id}) so that duplicates are also rejected after a restart.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationMessageConsumer {

    private final NotificationDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    @KafkaListener(topics = "${app.notification.consumer.topic}", groupId = "${app.notification.consumer.group-id}")
    public void onMessage(PlatformEvent event) {
        if (event == null || event.eventType() == null) {
            log.warn("NOTIFICATION_CONSUMER_SKIP | null event or type");
            return;
        }
        if (!NotificationRequestEvent.EVENT_TYPE.equals(event.eventType())) {
            log.debug("NOTIFICATION_CONSUMER_SKIP | type={}", event.eventType());
            return;
        }
        try {
            NotificationRequestEvent request = objectMapper.readValue(
                    event.payload(), NotificationRequestEvent.class);

            if (request.eventId() == null) {
                log.warn("NOTIFICATION_CONSUMER_SKIP | missing eventId");
                return;
            }
            // Idempotency: skip if already dispatched within the rolling window.
            if (!seenEventIds.add(request.eventId())) {
                log.debug("NOTIFICATION_CONSUMER_DUPLICATE | eventId={}", request.eventId());
                return;
            }
            dispatchService.dispatch(request);
            log.info("NOTIFICATION_CONSUMER_DISPATCHED | eventId={} | channel={} | userId={}",
                    request.eventId(), request.channel(), request.userId());
        } catch (Exception ex) {
            log.error("NOTIFICATION_CONSUMER_FAILED | aggregateId={} | error={}",
                    event.aggregateId(), ex.getMessage(), ex);
        }
    }
}