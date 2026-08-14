package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.bhukkad.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLiveUpdateBroadcaster {

    private final OrderLiveRelay orderLiveRelay;
    private final OrderLiveReplayStore orderLiveReplayStore;

    public void broadcastStatusChange(OrderStatusChangedEvent event) {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED)
                .orderId(event.orderId())
                .orderNumber(event.orderNumber())
                .customerId(event.customerId())
                .restaurantId(event.restaurantId())
                .deliveryAgentId(event.deliveryAgentId())
                .previousStatus(event.previousStatus() != null ? event.previousStatus().name() : null)
                .status(event.newStatus().name())
                .changedAt(event.changedAt())
                .build();
        dispatch(update);
    }

    public void broadcastOrderCreated(OrderCreatedEvent event) {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.ORDER_CREATED)
                .orderId(event.orderId())
                .orderNumber(event.orderNumber())
                .customerId(event.customerId())
                .restaurantId(event.restaurantId())
                .previousStatus(null)
                .status(Order.OrderStatus.PLACED.name())
                .changedAt(event.createdAt())
                .build();
        dispatch(update);
    }

    public void broadcastAgentAssigned(OrderAgentAssignedEvent event) {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.AGENT_ASSIGNED)
                .orderId(event.orderId())
                .orderNumber(event.orderNumber())
                .customerId(event.customerId())
                .restaurantId(event.restaurantId())
                .deliveryAgentId(event.deliveryAgentId())
                .previousStatus(event.status().name())
                .status(event.status().name())
                .changedAt(event.assignedAt())
                .build();
        dispatch(update);
    }

    private void dispatch(OrderLiveUpdate update) {
        long eventId = orderLiveReplayStore.nextEventId();
        update.setEventId(eventId);
        orderLiveReplayStore.record(update);
        orderLiveRelay.publish(update);
        log.debug("LIVE_UPDATE_PUBLISHED | eventId={} | type={} | orderId={} | restaurantId={} | agentId={}",
                eventId, update.getEventType(), update.getOrderId(), update.getRestaurantId(), update.getDeliveryAgentId());
    }
}
