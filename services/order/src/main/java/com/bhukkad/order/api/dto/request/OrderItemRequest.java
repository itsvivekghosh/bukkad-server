package com.bhukkad.order.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Order item request with validation constraints. */
public record OrderItemRequest(
        @NotNull(message = "Menu item ID is required")
        @Positive(message = "Menu item ID must be positive")
        Long menuItemId,

        @NotBlank(message = "Item name is required")
        String name,

        @NotNull(message = "Unit price is required")
        @Positive(message = "Unit price must be positive")
        BigDecimal unitPrice,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity
) {
}
