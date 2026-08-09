package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {
    @NotNull(message = "Restaurant ID is required")
    private Long restaurantId;

    @NotEmpty(message = "Order items cannot be empty")
    private List<OrderItemRequest> items;

    @NotNull(message = "Delivery address ID is required")
    private Long deliveryAddressId;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    private String couponCode;

    @NotNull(message = "Payment method is required")
    private String paymentMethod;
}