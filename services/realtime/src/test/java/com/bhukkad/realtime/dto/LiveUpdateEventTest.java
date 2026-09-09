package com.bhukkad.realtime.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveUpdateEventTest {

    @Test
    void constructorAndGetters() {
        LiveUpdateEvent event = new LiveUpdateEvent("ORDER_CREATED", "order-123");

        assertEquals("ORDER_CREATED", event.getType());
        assertEquals("order-123", event.getPayload());
    }

    @Test
    void setters() {
        LiveUpdateEvent event = new LiveUpdateEvent();

        event.setType("RIDER_LOCATION");
        event.setPayload("{\"lat\":12.9716}");

        assertEquals("RIDER_LOCATION", event.getType());
        assertEquals("{\"lat\":12.9716}", event.getPayload());
    }

    @Test
    void withObjectPayload() {
        LiveUpdateEvent event = new LiveUpdateEvent();
        event.setType("TEST");
        event.setPayload(new java.util.HashMap<>());

        assertNotNull(event.getPayload());
        assertTrue(event.getPayload() instanceof java.util.Map);
    }
}
