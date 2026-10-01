package com.bhukkad.commerce.order.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * Order create request with validation constraints for 200k+ TPS safety.
 *
 * <p>{@code items} is deliberately NOT annotated {@code @NotEmpty}. An omitted
 * item list is the documented cart-checkout path: {@code LegacyOrderCompatController}
 * falls back to a server-side snapshot of the customer's ACTIVE CART when the
 * list is null/empty. Bean validation runs before the controller body, so a
 * {@code @NotEmpty} here rejected every cart checkout with 400 "Order must
 * contain at least one item" and made that fallback unreachable. The
 * "must contain at least one item" invariant is still enforced in the
 * controller, after the cart has been resolved, so a genuinely empty cart
 * still fails with a business error.
 */
public record CreateOrderRequest(
        @NotNull(message = "Customer ID is required")
        Long customerId,

        @NotNull(message = "Restaurant ID is required")
        @Positive(message = "Restaurant ID must be positive")
        Long restaurantId,

        @Valid
        List<OrderItemRequest> items
) {
}
