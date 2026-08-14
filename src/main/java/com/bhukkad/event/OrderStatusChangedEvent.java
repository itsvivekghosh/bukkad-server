package com.bhukkad.event;

import com.bhukkad.entity.Order;

import java.time.LocalDateTime;

public record OrderStatusChangedEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        Long restaurantId,
        Long deliveryAgentId,
        Order.OrderStatus previousStatus,
        Order.OrderStatus newStatus,
        LocalDateTime changedAt
) {}
