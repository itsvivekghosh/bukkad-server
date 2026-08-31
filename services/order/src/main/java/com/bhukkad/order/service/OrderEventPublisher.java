package com.bhukkad.order.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Publishes order domain events via the transactional outbox (plan §6.2,
 * {@code order.events.v1}): {@code OrderCreated}, {@code OrderStatusChanged}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventPublisher {

    public static final String TOPIC = "order.events.v1";
    public static final String TYPE_ORDER_CREATED = "OrderCreated";
    public static final String TYPE_ORDER_STATUS_CHANGED = "OrderStatusChanged";

    private final OutboxClient outboxClient;

    public void orderCreated(Long orderId, Long customerId, Long restaurantId) {
        enqueue(TYPE_ORDER_CREATED, orderId,
                "{\"orderId\":%d,\"customerId\":%d,\"restaurantId\":%d}"
                        .formatted(orderId, customerId, restaurantId));
    }

    public void orderStatusChanged(Long orderId, String status) {
        enqueue(TYPE_ORDER_STATUS_CHANGED, orderId,
                "{\"orderId\":%d,\"status\":\"%s\"}".formatted(orderId, status));
    }

    private void enqueue(String type, Long aggregateId, String payload) {
        try {
            PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), payload);
            outboxClient.enqueue(message, aggregateId);
            log.info("ORDER_EVENT_ENQUEUED | type={} | orderId={}", type, aggregateId);
        } catch (Exception e) {
            log.error("ORDER_EVENT_ENQUEUE_FAILED | type={} | orderId={}", type, aggregateId, e);
        }
    }
}