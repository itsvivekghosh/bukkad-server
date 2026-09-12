package com.bhukkad.realtime.domain.service;

import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface OrderSseStreamService {

    SseEmitter subscribeKitchen(Long restaurantId, String lastEventId);

    SseEmitter subscribeRider(Long agentId, String lastEventId);

    SseEmitter subscribeCustomer(Long orderId, String lastEventId, Object snapshot);

    void broadcastKitchen(Long restaurantId, OrderLiveUpdate update);

    void broadcastRider(Long agentId, OrderLiveUpdate update);

    void broadcastCustomer(Long orderId, OrderLiveUpdate update);

    void sendHeartbeats();

    int activeConnectionCount();
}
