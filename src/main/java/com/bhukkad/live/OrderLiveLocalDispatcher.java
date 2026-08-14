package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderLiveLocalDispatcher {

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderSseStreamService sseStreamService;

    public void dispatch(OrderLiveUpdate update) {
        Long restaurantId = update.getRestaurantId();
        Long agentId = update.getDeliveryAgentId();

        messagingTemplate.convertAndSend(OrderLiveTopics.kitchen(restaurantId), update);
        sseStreamService.broadcastKitchen(restaurantId, update);

        Long customerId = update.getCustomerId();
        Long orderId = update.getOrderId();
        if (customerId != null && orderId != null) {
            messagingTemplate.convertAndSend(OrderLiveTopics.customer(orderId), update);
            sseStreamService.broadcastCustomer(orderId, update);
        }

        if (agentId != null) {
            messagingTemplate.convertAndSend(OrderLiveTopics.rider(agentId), update);
            sseStreamService.broadcastRider(agentId, update);
        }

        log.debug("LIVE_UPDATE_LOCAL | type={} | orderId={} | restaurantId={} | agentId={}",
                update.getEventType(), update.getOrderId(), restaurantId, agentId);
    }
}
