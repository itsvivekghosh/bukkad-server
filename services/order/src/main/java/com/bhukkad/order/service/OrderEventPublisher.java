package com.bhukkad.order.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Publishes order domain events via the transactional outbox (plan §6.2,
 * {@code order.events.v1}): {@code OrderCreated}, {@code OrderStatusChanged}.
 *
 * <p><strong>G-1 contract (audit §8, PERF-2/D6):</strong> enqueue failures
 * PROPAGATE. The swallow that used to live here logged
 * {@code ORDER_EVENT_ENQUEUE_FAILED} and let the order commit with no event —
 * exactly the lost-event class G-1 forbids. An order row and its outbox row
 * are one atomic unit: if the event cannot be recorded, the business
 * transaction must roll back with it. Every call site therefore runs inside a
 * {@code @Transactional} service method ({@code OrderService},
 * {@code OrderStatusService}, {@code ScheduledOrderProcessor}'s per-order
 * {@code TransactionTemplate}); {@link OutboxClient} additionally hard-throws
 * a {@code G-1 violation} when no transaction is active.</p>
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
        PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), payload);
        // No try/catch: the exception must reach the caller so its @Transactional
        // rolls back together with the order change (G-1).
        outboxClient.enqueue(message, aggregateId);
        log.info("ORDER_EVENT_ENQUEUED | type={} | orderId={}", type, aggregateId);
    }
}
