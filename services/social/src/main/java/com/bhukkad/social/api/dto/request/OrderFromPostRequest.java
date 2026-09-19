package com.bhukkad.social.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to create an order from a social post.
 */
public record OrderFromPostRequest(
        @NotNull
        @Size(min = 1, message = "At least one menu item is required")
        List<OrderItemRequest> items
) {

    /**
     * Request for a single menu item to order.
     * Note: name and unitPrice are ignored server-side for security;
     * only menuItemId and quantity are used from the client request.
     */
    public record OrderItemRequest(
            @NotNull(message = "menuItemId is required")
            Long menuItemId,

            @NotBlank(message = "item name is required")
            String name,

            @NotNull(message = "unitPrice is required")
            java.math.BigDecimal unitPrice,

            @Positive(message = "quantity must be positive")
            int quantity
    ) {
    }
}