package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.entity.Order;
import com.bhukkad.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLiveUpdateBroadcaster {

    private final OrderLiveRelay orderLiveRelay;
    private final OrderLiveReplayStore orderLiveReplayStore;
    private final OrderRepository orderRepository;
    private final OrderEtaService orderEtaService;

    public void broadcastStatusChange(OrderStatusChangedEvent event) {
        OrderLiveUpdate update = baseUpdate(event.orderId(), event.orderNumber(), event.customerId(),
                event.restaurantId(), event.deliveryAgentId(), OrderLiveUpdate.EventType.STATUS_CHANGED);
        update.setPreviousStatus(event.previousStatus() != null ? event.previousStatus().name() : null);
        update.setStatus(event.newStatus().name());
        update.setChangedAt(event.changedAt());
        dispatch(update);
    }

    public void broadcastOrderCreated(OrderCreatedEvent event) {
        OrderLiveUpdate update = baseUpdate(event.orderId(), event.orderNumber(), event.customerId(),
                event.restaurantId(), null, OrderLiveUpdate.EventType.ORDER_CREATED);
        update.setPreviousStatus(null);
        update.setStatus(Order.OrderStatus.PLACED.name());
        update.setChangedAt(event.createdAt());
        dispatch(update);
    }

    public void broadcastAgentAssigned(OrderAgentAssignedEvent event) {
        OrderLiveUpdate update = baseUpdate(event.orderId(), event.orderNumber(), event.customerId(),
                event.restaurantId(), event.deliveryAgentId(), OrderLiveUpdate.EventType.AGENT_ASSIGNED);
        update.setPreviousStatus(event.status().name());
        update.setStatus(event.status().name());
        update.setChangedAt(event.assignedAt());
        dispatch(update);
    }

    /**
     * Streams a rider GPS snapshot to the customer's order-live topic so the
     * app can render a live map. Called from {@code RiderLocationService} on
     * every agent location ping.
     */
    public void broadcastRiderLocation(Long orderId, Long customerId, Long restaurantId,
                                       Long agentId, double latitude, double longitude) {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.RIDER_LOCATION)
                .orderId(orderId)
                .customerId(customerId)
                .restaurantId(restaurantId)
                .deliveryAgentId(agentId)
                .changedAt(java.time.LocalDateTime.now())
                .latitude(latitude)
                .longitude(longitude)
                .build();
        dispatch(update);
    }

    private OrderLiveUpdate baseUpdate(Long orderId, String orderNumber, Long customerId, Long restaurantId,
                                       Long agentId, OrderLiveUpdate.EventType type) {
        OrderLiveUpdate.OrderLiveUpdateBuilder builder = OrderLiveUpdate.builder()
                .eventType(type)
                .orderId(orderId)
                .orderNumber(orderNumber)
                .customerId(customerId)
                .restaurantId(restaurantId)
                .deliveryAgentId(agentId);
        orderRepository.findByIdWithDetails(orderId).ifPresent(order -> {
            var eta = orderEtaService.computeLiveEta(order);
            builder.liveEtaMinutes(eta.minutes()).liveEtaAt(eta.etaAt());
        });
        return builder.build();
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
