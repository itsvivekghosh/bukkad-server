package com.bhukkad.engagement.chat.ai;

import java.util.Optional;

/**
 * Tool interface for tracking order delivery status.
 */
public interface OrderTrackingTool {

    /**
     * Gets the current tracking status for an order.
     *
     * @param customerId the customer ID
     * @param orderId    the order ID
     * @return tracking info or empty if not found/not owned
     */
    Optional<TrackingInfo> trackOrder(Long customerId, Long orderId);

    /**
     * Tracking information.
     */
    record TrackingInfo(
            Long orderId,
            String status,              // e.g., "PREPARING", "PICKED_UP", "DELIVERING", "DELIVERED"
            String restaurantName,
            String riderName,
            String riderPhone,
            Double riderLat,
            Double riderLng,
            String estimatedArrival,
            String lastUpdate
    ) {}
}