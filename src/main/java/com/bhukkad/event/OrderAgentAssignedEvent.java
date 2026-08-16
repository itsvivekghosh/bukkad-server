package com.bhukkad.event;

import com.bhukkad.entity.Order;

import java.time.LocalDateTime;

public record OrderAgentAssignedEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        Long restaurantId,
        Long deliveryAgentId,
        Order.OrderStatus status,
        LocalDateTime assignedAt
) {}
