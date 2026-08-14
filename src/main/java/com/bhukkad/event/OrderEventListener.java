package com.bhukkad.event;

import com.bhukkad.live.OrderLiveUpdateBroadcaster;
import com.bhukkad.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderLiveUpdateBroadcaster liveUpdateBroadcaster;
    private final NotificationService notificationService;

    @Async("orderTaskExecutor")
    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        log.info("ORDER_STATUS_CHANGED | orderId={} | orderNumber={} | {} -> {} | restaurantId={}",
                event.orderId(),
                event.orderNumber(),
                event.previousStatus(),
                event.newStatus(),
                event.restaurantId());
        liveUpdateBroadcaster.broadcastStatusChange(event);
        notificationService.sendOrderStatusUpdate(event.orderId(), event.newStatus().name());
    }

    @Async("orderTaskExecutor")
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("ORDER_CREATED | orderId={} | orderNumber={} | restaurantId={}",
                event.orderId(), event.orderNumber(), event.restaurantId());
        liveUpdateBroadcaster.broadcastOrderCreated(event);
        notificationService.sendOrderConfirmation(event.orderId());
    }

    @Async("orderTaskExecutor")
    @EventListener
    public void onOrderAgentAssigned(OrderAgentAssignedEvent event) {
        log.info("ORDER_AGENT_ASSIGNED | orderId={} | orderNumber={} | agentId={}",
                event.orderId(), event.orderNumber(), event.deliveryAgentId());
        liveUpdateBroadcaster.broadcastAgentAssigned(event);
        notificationService.sendDeliveryAssignment(event.orderId(), event.deliveryAgentId());
    }
}
