package com.bhukkad.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Customer request to file a dispute against an order. */
public record DisputeRequest(
        @NotNull(message = "Dispute type is required")
        String type,

        @NotBlank(message = "Customer evidence is required for evidence-based resolution")
        String customerEvidence
) {
}