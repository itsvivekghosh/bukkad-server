package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BatchOrderRequest {
    @NotNull(message = "Delivery address ID is required")
    private Long deliveryAddressId;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    @NotNull(message = "Payment method is required")
    private String paymentMethod;

    private Double tipAmount;
}
