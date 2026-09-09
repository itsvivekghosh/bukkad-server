package com.bhukkad.delivery.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryEventPublisher {
    public static final String TYPE_ASSIGNED = "DeliveryAssigned";
    public static final String TYPE_PICKED_UP = "OrderPickedUp";
    public static final String TYPE_DELIVERED = "OrderDelivered";
    private final OutboxClient outboxClient;

    public void deliveryAssigned(Long orderId, Long agentId) {
        enqueue(TYPE_ASSIGNED, orderId, "{\"orderId\":%d,\"agentId\":%d}".formatted(orderId, agentId));
    }

    public void orderDelivered(Long orderId, Long agentId) {
        enqueue(TYPE_DELIVERED, orderId, "{\"orderId\":%d,\"agentId\":%d}".formatted(orderId, agentId));
    }

    private void enqueue(String type, Long aggregateId, String payload) {
        try {
            outboxClient.enqueue(PlatformEventMessage.of(type, String.valueOf(aggregateId), payload), aggregateId);
            log.info("DELIVERY_EVENT_ENQUEUED | type={} | orderId={}", type, aggregateId);
        } catch (Exception e) {
            log.error("DELIVERY_EVENT_ENQUEUE_FAILED | type={} | orderId={}", type, aggregateId, e);
        }
    }
}