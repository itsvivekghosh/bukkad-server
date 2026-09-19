package com.bhukkad.social.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.social.api.dto.request.OrderFromPostRequest;
import com.bhukkad.social.domain.entity.PostOrderConversion;
import com.bhukkad.social.domain.service.PostOrderConversionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Publishes order-from-post domain events via the transactional outbox.
 * 
 * <p><strong>G-1 contract (audit §8, PERF-2/D6):</strong> enqueue failures
 * PROPAGATE. The swallow that used to live here logged
 * {@code ORDER_EVENT_ENQUEUE_FAILED} and let the order commit with no event —
 * exactly the lost-event class G-1 forbids. An outbox row and its payload
 * are one atomic unit: if the event cannot be recorded, the business
 * transaction must roll back with it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFromPostEventPublisher {

    public static final String TOPIC = "social.events.v1";
    public static final String TYPE_ORDER_FROM_POST = "OrderFromPost";

    private final OutboxClient outboxClient;
    private final PostOrderConversionService postOrderConversionService;

    /**
     * Publish an order-from-post event and record conversion analytics.
     * 
     * @param postId the ID of the social post that triggered the order
     * @param userId the ID of the user who created the order
     * @param restaurantId the ID of the restaurant being ordered from
     * @param orderId the ID of the created order
     * @param totalAmount the total order amount
     * @param items the menu items that were ordered
     */
    public void publishOrderFromPostEvent(
            Long postId, Long userId, Long restaurantId, Long orderId, BigDecimal totalAmount,
            List<OrderFromPostRequest.OrderItemRequest> items) {
        
        // Build the payload
        StringBuilder itemJson = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            OrderFromPostRequest.OrderItemRequest item = items.get(i);
            if (i > 0) {
                itemJson.append(',');
            }
            itemJson.append("{\"menuItemId\":").append(item.menuItemId())
                    .append(",\"quantity\":").append(item.quantity()).append('}');
        }
        itemJson.append(']');
        
        String payload = String.format(
                "{\"postId\":%d,\"userId\":%d,\"restaurantId\":%d,\"orderId\":%d,\"items\":%s}",
                postId, userId, restaurantId, orderId, itemJson);
        
        // Record conversion analytics (same transaction as outbox enqueue)
        postOrderConversionService.recordConversion(postId, orderId, userId, restaurantId, totalAmount, items);
        
        // Enqueue the event via the outbox (transactional)
        outboxClient.enqueue(TYPE_ORDER_FROM_POST, orderId, payload);
        log.info("ORDER_FROM_POST_EVENT_ENQUEUED | postId={} | orderId={}", postId, orderId);
    }
}