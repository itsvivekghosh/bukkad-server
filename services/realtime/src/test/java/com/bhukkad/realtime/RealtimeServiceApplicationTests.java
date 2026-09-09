package com.bhukkad.realtime;

import com.bhukkad.realtime.dto.OrderLiveUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeServiceApplicationTests {

    @Test
    void orderLiveUpdateBuilderWorks() {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED)
                .orderId(1L)
                .eventId(100L)
                .status("DELIVERED")
                .build();

        assertEquals(OrderLiveUpdate.EventType.STATUS_CHANGED, update.getEventType());
        assertEquals(1L, update.getOrderId());
        assertEquals(100L, update.getEventId());
        assertEquals("DELIVERED", update.getStatus());
    }

    @Test
    void orderLiveUpdateAllFields() {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.RIDER_LOCATION)
                .eventId(1L)
                .orderId(100L)
                .orderNumber("ORD-001")
                .customerId(10L)
                .restaurantId(5L)
                .deliveryAgentId(3L)
                .latitude(12.9716)
                .longitude(77.5946)
                .liveEtaMinutes(25)
                .build();

        assertEquals(12.9716, update.getLatitude());
        assertEquals(77.5946, update.getLongitude());
        assertEquals(25, update.getLiveEtaMinutes());
    }
}
