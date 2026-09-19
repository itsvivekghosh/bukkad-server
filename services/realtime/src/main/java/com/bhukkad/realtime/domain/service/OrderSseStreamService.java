package com.bhukkad.realtime.domain.service;

import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Pod-local reactive SSE registry for the live order streams.
 *
 * <p>Returns {@link ServerSentEvent} fluxes so the controller can stream
 * updates without holding a servlet thread per connection.</p>
 */
public interface OrderSseStreamService {

    /**
     * Subscribe to kitchen updates for a restaurant.
     *
     * @return a cold Flux that emits SSE events for this stream
     */
    Flux<ServerSentEvent<String>> subscribeKitchen(Long restaurantId, String lastEventId);

    /**
     * Subscribe to rider updates for an agent.
     *
     * @return a cold Flux that emits SSE events for this stream
     */
    Flux<ServerSentEvent<String>> subscribeRider(Long agentId, String lastEventId);

    /**
     * Subscribe to customer order updates.
     *
     * @return a cold Flux that emits SSE events for this stream
     */
    Flux<ServerSentEvent<String>> subscribeCustomer(Long orderId, String lastEventId, Object snapshot);

    /**
     * Broadcast an update to all kitchen subscribers for a restaurant.
     */
    void broadcastKitchen(Long restaurantId, OrderLiveUpdate update);

    /**
     * Broadcast an update to all rider subscribers for an agent.
     */
    void broadcastRider(Long agentId, OrderLiveUpdate update);

    /**
     * Broadcast an update to all customer subscribers for an order.
     */
    void broadcastCustomer(Long orderId, OrderLiveUpdate update);

    /**
     * Send heartbeats to all active streams.
     */
    void sendHeartbeats();

    /**
     * Count the number of active SSE connections across all streams.
     */
    int activeConnectionCount();
}
