package com.bhukkad.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DisputeRequest {

    @NotNull
    private DisputeType type;

    @NotBlank
    private String customerEvidence;

    public enum DisputeType {
        ORDER_NOT_RECEIVED,
        WRONG_ORDER,
        LATE_DELIVERY,
        FOOD_QUALITY,
        PAYMENT_ISSUE,
        OTHER
    }
}
