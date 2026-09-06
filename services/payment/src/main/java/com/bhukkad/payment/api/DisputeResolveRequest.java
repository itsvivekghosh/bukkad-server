package com.bhukkad.payment.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DisputeResolveRequest {

    @NotNull
    private DisputeResolution resolution;

    private Double refundAmount;

    private String notes;

    public enum DisputeResolution {
        FULL_REFUND,
        PARTIAL_REFUND,
        NO_REFUND,
        CREDIT_ISSUED,
        ESCALATED
    }
}
