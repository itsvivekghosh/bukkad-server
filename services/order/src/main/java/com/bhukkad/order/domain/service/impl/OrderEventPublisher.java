package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.order.infrastructure.client.StockReservationLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import com.bhukkad.order.infrastructure.client.StockReservationLine;

/**
 * Publishes order domain events via the transactional outbox (plan §6.2,
 * {@code order.events.v1}): {@code OrderCreated}, {@code OrderStatusChanged},
 * plus the saga contract events {@code payment_requested},
 * {@code order_items_snapshot} and {@code stock_release_requested}.
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
    /** Saga contract (feature #3): order → payment charge request. W1-MONEY owns the consumer. */
    public static final String TYPE_PAYMENT_REQUESTED = "payment_requested";
    /** Trending feed (ADR-002 / survey): item breakdown snapshot consumed by survey. */
    public static final String TYPE_ORDER_ITEMS_SNAPSHOT = "ORDER_ITEMS_SNAPSHOT";
    /** Reverse outbox event (G-1): compensation must never call brokers/HTTP inline. */
    public static final String TYPE_STOCK_RELEASE_REQUESTED = "stock_release_requested";

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

    /**
     * W1-MONEY consumer contract — payload shape is frozen:
     * {@code orderId, customerId, amount, currency, idempotencyKey}.
     * Amount serializes as a plain decimal string; the idempotency key is
     * {@code ORDER-<id>} so payment-side retries cannot double charge.
     */
    public void paymentRequested(Long orderId, Long customerId, java.math.BigDecimal amount, String currency) {
        enqueue(TYPE_PAYMENT_REQUESTED, orderId,
                "{\"orderId\":%d,\"customerId\":%d,\"amount\":\"%s\",\"currency\":\"%s\",\"idempotencyKey\":\"ORDER-%d\"}"
                        .formatted(orderId, customerId, amount.toPlainString(), currency, orderId));
    }

    /**
     * Survey's {@code OrderItemsSnapshotConsumer} contract — payload shape is
     * frozen: {@code orderId, restaurantId, items[{menuItemId, name, quantity}],
     * orderedAt}. {@code orderedAt} is ISO-8601 local date-time (the consumer
     * parses it with {@link java.time.format.DateTimeFormatter#ISO_DATE_TIME}).
     */
    /** Item line of the {@code order_items_snapshot} payload (survey contract). */
    public record SnapshotItem(long menuItemId, String name, long quantity) {
    }

    public void orderItemsSnapshot(Long orderId, Long restaurantId, java.util.List<SnapshotItem> items) {
        StringBuilder itemJson = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            SnapshotItem item = items.get(i);
            if (i > 0) {
                itemJson.append(',');
            }
            itemJson.append("{\"menuItemId\":").append(item.menuItemId())
                    .append(",\"name\":\"").append(escape(item.name()))
                    .append("\",\"quantity\":").append(item.quantity()).append('}');
        }
        itemJson.append(']');
        enqueue(TYPE_ORDER_ITEMS_SNAPSHOT, orderId,
                "{\"orderId\":%d,\"restaurantId\":%d,\"items\":%s,\"orderedAt\":\"%s\"}"
                        .formatted(orderId, restaurantId, itemJson,
                                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_DATE_TIME)));
    }

    /**
     * Compensation event (feature #3/G-1): releases stock reserved by an
     * earlier {@code reserveStock} call. Emitted into the outbox instead of a
     * synchronous HTTP call so a compensation can never hold a DB transaction
     * open on broker/HTTP I/O.
     */
    public void stockReleaseRequested(Long orderId, List<StockReservationLine> lines) {
        StringBuilder linesJson = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            StockReservationLine line = lines.get(i);
            if (i > 0) {
                linesJson.append(',');
            }
            linesJson.append("{\"menuItemId\":").append(line.menuItemId())
                    .append(",\"name\":\"").append(escape(line.menuItemName()))
                    .append("\",\"quantity\":").append(line.quantity()).append('}');
        }
        linesJson.append(']');
        enqueue(TYPE_STOCK_RELEASE_REQUESTED, orderId,
                "{\"orderId\":%d,\"lines\":%s}".formatted(orderId, linesJson));
    }

    private void enqueue(String type, Long aggregateId, String payload) {
        PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), payload);
        // No try/catch: the exception must reach the caller so its @Transactional
        // rolls back together with the order change (G-1).
        outboxClient.enqueue(message, aggregateId);
        log.info("ORDER_EVENT_ENQUEUED | type={} | orderId={}", type, aggregateId);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
