package com.bhukkad.order.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Internal-facing order detail view for cross-service consumers (e.g. supportticket dispute
 * auto-resolution). Unlike {@link OrderResponse}, this projection includes delivery
 * timestamps needed for late-delivery refund calculations.
 */
public record OrderDetailsResponse(
        Long orderId,
        Long customerId,
        Long restaurantId,
        String status,
        BigDecimal totalAmount,
        LocalDateTime deliveredAt,
        LocalDateTime estimatedDeliveryAt
) {
}
