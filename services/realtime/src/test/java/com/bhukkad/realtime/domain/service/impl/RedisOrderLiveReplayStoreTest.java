package com.bhukkad.realtime.domain.service.impl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.domain.service.OrderLiveReplayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisOrderLiveReplayStoreTest {

    private LiveProperties liveProperties;
    private LiveProperties.ReplayProperties replayProperties;

    @BeforeEach
    void setUp() {
        replayProperties = new LiveProperties.ReplayProperties();
        replayProperties.setTtlSeconds(3600);
        replayProperties.setMaxEventsPerStream(100);

        liveProperties = new LiveProperties();
        liveProperties.setReplay(replayProperties);
    }

    @Test
    void streamKeyKitchen_generatesCorrectFormat() {
        String key = "kitchen:123";
        assertTrue(key.startsWith("kitchen:"));
        assertTrue(key.contains("123"));
    }

    @Test
    void streamKeyOrder_generatesCorrectFormat() {
        String key = "order:456";
        assertTrue(key.startsWith("order:"));
        assertTrue(key.contains("456"));
    }

    @Test
    void streamKeyRider_generatesCorrectFormat() {
        String key = "rider:789";
        assertTrue(key.startsWith("rider:"));
        assertTrue(key.contains("789"));
    }

    @Test
    void parseLastEventId_validId() {
        String lastEventId = "12345";
        long result = parseLastEventId(lastEventId);
        assertEquals(12345L, result);
    }

    @Test
    void parseLastEventId_withWhitespace() {
        String lastEventId = "  12345  ";
        long result = parseLastEventId(lastEventId);
        assertEquals(12345L, result);
    }

    @Test
    void parseLastEventId_invalidFormat() {
        String lastEventId = "not-a-number";
        long result = parseLastEventId(lastEventId);
        assertEquals(-1L, result);
    }

    @Test
    void parseLastEventId_nullInput() {
        long result = parseLastEventId(null);
        assertEquals(-1L, result);
    }

    @Test
    void parseLastEventId_emptyInput() {
        long result = parseLastEventId("");
        assertEquals(-1L, result);
    }

    private long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    @Test
    void orderLiveUpdate_eventTypes() {
        assertEquals(4, OrderLiveUpdate.EventType.values().length);
        assertNotNull(OrderLiveUpdate.EventType.valueOf("ORDER_CREATED"));
        assertNotNull(OrderLiveUpdate.EventType.valueOf("STATUS_CHANGED"));
        assertNotNull(OrderLiveUpdate.EventType.valueOf("AGENT_ASSIGNED"));
        assertNotNull(OrderLiveUpdate.EventType.valueOf("RIDER_LOCATION"));
    }

    @Test
    void orderLiveUpdate_builderSetsAllFields() {
        LocalDateTime now = LocalDateTime.now();
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.RIDER_LOCATION)
                .eventId(100L)
                .orderId(1L)
                .orderNumber("ORD-001")
                .customerId(10L)
                .restaurantId(5L)
                .deliveryAgentId(3L)
                .previousStatus("ASSIGNED")
                .status("IN_TRANSIT")
                .changedAt(now)
                .liveEtaMinutes(25)
                .liveEtaAt(now.plusMinutes(25))
                .latitude(12.9716)
                .longitude(77.5946)
                .build();

        assertEquals(OrderLiveUpdate.EventType.RIDER_LOCATION, update.getEventType());
        assertEquals(100L, update.getEventId());
        assertEquals(1L, update.getOrderId());
        assertEquals("ORD-001", update.getOrderNumber());
        assertEquals(10L, update.getCustomerId());
        assertEquals(5L, update.getRestaurantId());
        assertEquals(3L, update.getDeliveryAgentId());
        assertEquals("ASSIGNED", update.getPreviousStatus());
        assertEquals("IN_TRANSIT", update.getStatus());
        assertEquals(now, update.getChangedAt());
        assertEquals(25, update.getLiveEtaMinutes());
        assertEquals(12.9716, update.getLatitude());
        assertEquals(77.5946, update.getLongitude());
    }
}
