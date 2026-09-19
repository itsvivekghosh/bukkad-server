package com.bhukkad.order.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/** Order create request with validation constraints for 200k+ TPS safety. */
public record CreateOrderRequest(
        @NotNull(message = "Customer ID is required")
        Long customerId,

        @NotNull(message = "Restaurant ID is required")
        @Positive(message = "Restaurant ID must be positive")
        Long restaurantId,

        @NotEmpty(message = "Order must contain at least one item")
        @Valid
        List<OrderItemRequest> items
) {
}
