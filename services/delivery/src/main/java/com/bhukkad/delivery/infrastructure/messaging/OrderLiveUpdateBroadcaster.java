package com.bhukkad.delivery.live;

import com.bhukkad.delivery.api.EtaPort;
import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLiveUpdateBroadcaster {

    private final OrderLiveRelay orderLiveRelay;
    private final OrderLiveReplayStore orderLiveReplayStore;
    private final EtaPort etaPort;

    public void broadcastRiderLocation(Long orderId, Long customerId, Long restaurantId,
                                        Long agentId, double latitude, double longitude,
                                        String orderNumber, Integer liveEtaMinutes,
                                        LocalDateTime liveEtaAt) {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.RIDER_LOCATION)
                .orderId(orderId)
                .orderNumber(orderNumber)
                .customerId(customerId)
                .restaurantId(restaurantId)
                .deliveryAgentId(agentId)
                .changedAt(LocalDateTime.now())
                .latitude(latitude)
                .longitude(longitude)
                .liveEtaMinutes(liveEtaMinutes)
                .liveEtaAt(liveEtaAt)
                .build();
        dispatch(update);
    }

    public void broadcastRiderLocation(Long orderId, Long customerId, Long restaurantId,
                                        Long agentId, double latitude, double longitude) {
        broadcastRiderLocation(orderId, customerId, restaurantId, agentId,
                latitude, longitude, null, null, null);
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
        etaPort.computeEta(orderId).ifPresent(eta ->
                builder.liveEtaMinutes(eta.minutes()).liveEtaAt(eta.etaAt()));
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
