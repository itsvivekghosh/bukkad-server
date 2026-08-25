package com.bhukkad.event;

import com.bhukkad.entity.Order;
import com.bhukkad.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final OutboxEventService outboxEventService;

    public void publishStatusChange(Order order, Order.OrderStatus previousStatus) {
        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomer().getId(),
                order.getRestaurant().getId(),
                order.getDeliveryAgent() != null ? order.getDeliveryAgent().getId() : null,
                previousStatus,
                order.getStatus(),
                LocalDateTime.now()
        );
        outboxEventService.enqueue("ORDER_STATUS_CHANGED", order.getId(), event);
        log.debug("ORDER_STATUS_OUTBOX | orderId={} | {} -> {}",
                order.getId(), previousStatus, order.getStatus());
    }

    public void publishCreated(Order order) {
        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomer().getId(),
                order.getRestaurant().getId(),
                LocalDateTime.now()
        );
        outboxEventService.enqueue("ORDER_CREATED", order.getId(), event);
        log.debug("ORDER_CREATED_OUTBOX | orderId={} | restaurantId={}",
                order.getId(), order.getRestaurant().getId());
    }

    /**
     * Publishes a denormalized snapshot of the order's items so downstream
     * ANALYTICS consumers (trending dishes) can materialize their own tables
     * without joining the ORDER and RESTAURANT/MENU schemas.
     */
    public void publishItemsSnapshot(Order order) {
        if (order == null || order.getId() == null || order.getOrderItems() == null) {
            log.warn("Order items snapshot skipped | orderId={}", order != null ? order.getId() : null);
            return;
        }
        List<OrderItemsSnapshotEvent.Item> items = order.getOrderItems().stream()
                .filter(oi -> oi.getMenuItem() != null)
                .map(oi -> new OrderItemsSnapshotEvent.Item(
                        oi.getMenuItem().getId(),
                        oi.getMenuItem().getName(),
                        oi.getQuantity()))
                .toList();
        if (items.isEmpty()) {
            log.debug("Order items snapshot skipped (no items) | orderId={}", order.getId());
            return;
        }
        OrderItemsSnapshotEvent event = new OrderItemsSnapshotEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getRestaurant() != null ? order.getRestaurant().getId() : null,
                items,
                LocalDateTime.now()
        );
        outboxEventService.enqueue("ORDER_ITEMS_SNAPSHOT", order.getId(), event);
        log.debug("ORDER_ITEMS_SNAPSHOT_OUTBOX | orderId={} | items={}", order.getId(), items.size());
    }

    public void publishAgentAssigned(Order order) {
        OrderAgentAssignedEvent event = new OrderAgentAssignedEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomer().getId(),
                order.getRestaurant().getId(),
                order.getDeliveryAgent() != null ? order.getDeliveryAgent().getId() : null,
                order.getStatus(),
                LocalDateTime.now()
        );
        outboxEventService.enqueue("ORDER_AGENT_ASSIGNED", order.getId(), event);
        log.debug("ORDER_AGENT_ASSIGNED_OUTBOX | orderId={} | agentId={}",
                order.getId(), order.getDeliveryAgent() != null ? order.getDeliveryAgent().getId() : null);
    }
}
