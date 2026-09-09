package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderLiveLocalDispatcher {

    private final OrderSseStreamService sseStreamService;
    private final SimpMessagingTemplate messagingTemplate;

    public OrderLiveLocalDispatcher(OrderSseStreamService sseStreamService,
                                    @Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.sseStreamService = sseStreamService;
        this.messagingTemplate = messagingTemplate;
    }

    public void dispatch(OrderLiveUpdate update) {
        Long restaurantId = update.getRestaurantId();
        Long agentId = update.getDeliveryAgentId();

        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend(OrderLiveTopics.kitchen(restaurantId), update);
        }
        sseStreamService.broadcastKitchen(restaurantId, update);

        Long customerId = update.getCustomerId();
        Long orderId = update.getOrderId();
        if (customerId != null && orderId != null) {
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend(OrderLiveTopics.customer(orderId), update);
            }
            sseStreamService.broadcastCustomer(orderId, update);
        }

        if (agentId != null) {
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend(OrderLiveTopics.rider(agentId), update);
            }
            sseStreamService.broadcastRider(agentId, update);
        }

        log.debug("LIVE_UPDATE_LOCAL | type={} | orderId={} | restaurantId={} | agentId={}",
                update.getEventType(), update.getOrderId(), restaurantId, agentId);
    }
}
