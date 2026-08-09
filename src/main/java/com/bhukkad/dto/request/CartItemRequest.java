package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class CartItemRequest {
    @NotNull(message = "Menu item ID is required")
    private Long menuItemId;

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private Integer quantity;

    private List<Long> customizationChoiceIds;

    private String specialInstructions;
}