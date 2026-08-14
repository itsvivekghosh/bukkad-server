package com.bhukkad.event;

import java.time.LocalDateTime;

public record OrderCreatedEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        Long restaurantId,
        LocalDateTime createdAt
) {}
