package com.bhukkad.event;

import com.bhukkad.entity.Order;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderStatusChangedEventTest {

    @Test
    void recordStoresAllFields() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 8, 14, 22, 0);
        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
                1L, "ORD-1", 2L, 3L, null,
                Order.OrderStatus.PLACED, Order.OrderStatus.CONFIRMED, changedAt);

        assertEquals(1L, event.orderId());
        assertEquals("ORD-1", event.orderNumber());
        assertEquals(2L, event.customerId());
        assertEquals(3L, event.restaurantId());
        assertNull(event.deliveryAgentId());
        assertEquals(Order.OrderStatus.PLACED, event.previousStatus());
        assertEquals(Order.OrderStatus.CONFIRMED, event.newStatus());
        assertEquals(changedAt, event.changedAt());
    }
}
