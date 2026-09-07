package com.bhukkad.realtime.serviceImpl;

import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.bhukkad.realtime.service.OrderLiveRelay;
import com.bhukkad.realtime.service.OrderSseStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cross-pod fan-out bridge: without it Redis channel updates never reached
 * the local SSE emitters at all, so the only instance receiving a Kafka
 * event ever showed it to clients (dead relay — audit finding D).
 *
 * <p>Routing is channel-derived, not field-derived: an update is published
 * to every stream it belongs to (order + kitchen + rider), so keying off
 * fields would deliver multiples to clients connected on several channels.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderLiveSseBridge {

    private static final String CHANNEL_PREFIX = "live:channel:";

    private final OrderLiveRelay relay;
    private final OrderSseStreamService streamService;

    @EventListener(ApplicationReadyEvent.class)
    public void wire() {
        relay.subscribeAll(this::route);
        log.info("LIVE_SSE_BRIDGE_WIRED");
    }

    void route(String channel, OrderLiveUpdate update) {
        try {
            String body = channel.startsWith(CHANNEL_PREFIX)
                    ? channel.substring(CHANNEL_PREFIX.length()) : channel;
            if (body.startsWith("order:") && update.getOrderId() != null) {
                streamService.broadcastCustomer(update.getOrderId(), update);
            } else if (body.startsWith("kitchen:") && update.getRestaurantId() != null) {
                streamService.broadcastKitchen(update.getRestaurantId(), update);
            } else if (body.startsWith("rider:") && update.getDeliveryAgentId() != null) {
                streamService.broadcastRider(update.getDeliveryAgentId(), update);
            }
        } catch (RuntimeException ex) {
            log.warn("LIVE_SSE_BRIDGE_ROUTE_FAILED | channel={} | error={}", channel, ex.getMessage());
        }
    }
}
