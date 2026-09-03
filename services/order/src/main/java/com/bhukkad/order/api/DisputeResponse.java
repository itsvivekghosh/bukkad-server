package com.bhukkad.order.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Dispute response (extracted from monolith DisputeResponse). */
public record DisputeResponse(
        Long id,
        Long orderId,
        String orderNumber,
        String type,
        String status,
        String customerEvidence,
        String riderEvidence,
        String restaurantEvidence,
        String resolutionNotes,
        String resolution,
        BigDecimal refundAmount,
        Long resolvedBy,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt
) {
}