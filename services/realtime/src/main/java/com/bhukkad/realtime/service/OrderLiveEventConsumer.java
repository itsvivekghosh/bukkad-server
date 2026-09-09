package com.bhukkad.realtime.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.realtime.dto.LiveUpdateEvent;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
 * {@code AdminCqrsEventConsumer} / {@code NotificationEventConsumer} precedent.
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

    private final OrderLiveRelay relay;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = TOPIC_ORDER_EVENTS,
            groupId = "${app.events.external.kafka.consumer-group:realtime-platform-consumer}")
    public void onOrderEvent(String payload) {
        try {
            PlatformEventMessage event = objectMapper.readValue(payload, PlatformEventMessage.class);
            LiveUpdateEvent live = toLiveUpdateEvent(event);
            if (live != null) {
                relay.relay(live);
                log.info("LIVE_EVENT_CONSUMED | type={} | aggregateId={} | orderId={}",
                        event.eventType(), event.aggregateId(), orderIdOf(live));
            }
        } catch (Exception ex) {
            log.error("LIVE_EVENT_CONSUME_FAILED | error={}", ex.getMessage(), ex);
        }
    }

    private LiveUpdateEvent toLiveUpdateEvent(PlatformEventMessage event) {
        try {
            JsonNode data = objectMapper.readTree(event.payload());
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
            if (TYPE_ORDER_CREATED.equals(event.eventType())) {
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
            log.debug("LIVE_EVENT_IGNORED | type={}", event.eventType());
            return null;
        } catch (Exception ex) {
            log.warn("LIVE_EVENT_MAPPING_FAILED | type={} | error={}",
                    event.eventType(), ex.getMessage());
            return null;
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
