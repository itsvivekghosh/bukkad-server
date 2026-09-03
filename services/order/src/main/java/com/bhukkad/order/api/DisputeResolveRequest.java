package com.bhukkad.order.api;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Admin manual resolution of an open dispute. */
public record DisputeResolveRequest(
        @NotNull(message = "Resolution is required")
        String resolution,

        /** Required for FULL_REFUND / PARTIAL_REFUND resolutions. */
        BigDecimal refundAmount,

        String notes
) {
}