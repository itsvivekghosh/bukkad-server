package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Admin manual resolution of an open dispute. */
@Data
public class DisputeResolveRequest {

    @NotNull(message = "Resolution is required")
    private String resolution;

    /** Required for FULL_REFUND / PARTIAL_REFUND resolutions. */
    private Double refundAmount;

    private String notes;
}
