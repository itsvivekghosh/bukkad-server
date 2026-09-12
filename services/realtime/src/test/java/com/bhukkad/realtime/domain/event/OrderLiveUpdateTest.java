package com.bhukkad.realtime.domain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderLiveUpdateTest {

    @Test
    void eventTypes() {
        assertEquals(4, OrderLiveUpdate.EventType.values().length);
        assertEquals(OrderLiveUpdate.EventType.ORDER_CREATED,
                OrderLiveUpdate.EventType.valueOf("ORDER_CREATED"));
        assertEquals(OrderLiveUpdate.EventType.STATUS_CHANGED,
                OrderLiveUpdate.EventType.valueOf("STATUS_CHANGED"));
        assertEquals(OrderLiveUpdate.EventType.AGENT_ASSIGNED,
                OrderLiveUpdate.EventType.valueOf("AGENT_ASSIGNED"));
        assertEquals(OrderLiveUpdate.EventType.RIDER_LOCATION,
                OrderLiveUpdate.EventType.valueOf("RIDER_LOCATION"));
    }

    @Test
    void builderWithMinimalFields() {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.ORDER_CREATED)
                .orderId(1L)
                .build();

        assertEquals(OrderLiveUpdate.EventType.ORDER_CREATED, update.getEventType());
        assertEquals(1L, update.getOrderId());
        assertNull(update.getEventId());
        assertNull(update.getStatus());
    }

    @Test
    void builderWithAllFields() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.RIDER_LOCATION)
                .eventId(100L)
                .orderId(1L)
                .orderNumber("ORD-123")
                .customerId(10L)
                .restaurantId(5L)
                .deliveryAgentId(3L)
                .previousStatus("ASSIGNED")
                .status("IN_TRANSIT")
                .changedAt(now)
                .liveEtaMinutes(15)
                .liveEtaAt(now.plusMinutes(15))
                .latitude(12.9716)
                .longitude(77.5946)
                .build();

        assertEquals(OrderLiveUpdate.EventType.RIDER_LOCATION, update.getEventType());
        assertEquals(100L, update.getEventId());
        assertEquals(1L, update.getOrderId());
        assertEquals("ORD-123", update.getOrderNumber());
        assertEquals(10L, update.getCustomerId());
        assertEquals(5L, update.getRestaurantId());
        assertEquals(3L, update.getDeliveryAgentId());
        assertEquals("ASSIGNED", update.getPreviousStatus());
        assertEquals("IN_TRANSIT", update.getStatus());
        assertEquals(now, update.getChangedAt());
        assertEquals(15, update.getLiveEtaMinutes());
        assertEquals(now.plusMinutes(15), update.getLiveEtaAt());
        assertEquals(12.9716, update.getLatitude());
        assertEquals(77.5946, update.getLongitude());
    }

    @Test
    void settersAndGetters() {
        OrderLiveUpdate update = new OrderLiveUpdate();

        update.setEventType(OrderLiveUpdate.EventType.STATUS_CHANGED);
        update.setOrderId(99L);
        update.setStatus("DELIVERED");

        assertEquals(OrderLiveUpdate.EventType.STATUS_CHANGED, update.getEventType());
        assertEquals(99L, update.getOrderId());
        assertEquals("DELIVERED", update.getStatus());
    }
}
