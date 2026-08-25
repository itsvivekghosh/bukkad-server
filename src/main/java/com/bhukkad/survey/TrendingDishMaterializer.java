package com.bhukkad.survey;

import com.bhukkad.event.OrderItemsSnapshotEvent;
import com.bhukkad.repository.TrendingDishRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes the ORDER_ITEMS_SNAPSHOT outbox event and upserts the ANALYTICS
 * domain's trending_dishes summary table. This replaces the old cross-domain
 * SQL join (order_items ⋈ menu_items) with event-driven materialization:
 * the ORDER domain publishes a denormalized snapshot, the ANALYTICS domain
 * owns and writes only its own table.
 *
 * <p>Runs on the low-priority async executor so a burst of order creations
 * never starves the order placement path. The outbox guarantees at-least-once
 * delivery; the upsert is idempotent (quantity_sold is incremented by the
 * snapshot's quantity, so replays compound nothing beyond the event's own
 * quantity).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingDishMaterializer {

    private final TrendingDishRepository trendingDishRepository;

    @Async("lowPriorityTaskExecutor")
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderItemsSnapshot(OrderItemsSnapshotEvent event) {
        if (event == null || event.items() == null || event.items().isEmpty()) {
            log.debug("Order items snapshot ignored | orderId={}", event != null ? event.orderId() : null);
            return;
        }
        for (OrderItemsSnapshotEvent.Item item : event.items()) {
            if (item == null || item.menuItemId() == null || item.name() == null) {
                continue;
            }
            int updated = trendingDishRepository.upsert(
                    item.menuItemId(),
                    event.restaurantId(),
                    item.name(),
                    item.quantity() == null ? 0 : item.quantity(),
                    event.orderedAt() != null ? event.orderedAt() : java.time.LocalDateTime.now());
            if (updated <= 0) {
                log.warn("Trending dish upsert had no effect | menuItemId={}", item.menuItemId());
            }
        }
        log.debug("Trending dishes materialized | orderId={} | items={}",
                event.orderId(), event.items().size());
    }
}
