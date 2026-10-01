package com.bhukkad.engagement.social.infrastructure.client.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Client-side mirror of the commerce order-create wire contract.
 *
 * <p>Deliberately declared here rather than imported from the commerce module.
 * Engagement talks to commerce over HTTP through
 * {@link com.bhukkad.engagement.social.infrastructure.client.OrderServiceClient},
 * so it only needs the JSON shape — not commerce's classes. Depending on the
 * commerce jar would have dragged its {@code db/migration-pg} onto this
 * service's classpath, and Flyway's {@code classpath:db/migration-pg} location
 * would then find several {@code V1__baseline.sql} files and refuse to start
 * ("Found more than one migration with version 1").
 *
 * <p>Fields must stay in sync with
 * {@code commerce}'s {@code CreateOrderRequest} / {@code OrderResponse}.
 */
public final class CommerceOrderContract {

    private CommerceOrderContract() {
    }

    /** Request body for {@code POST /api/v1/orders}. */
    public record CreateOrderRequest(
            Long customerId,
            Long restaurantId,
            List<OrderItemRequest> items
    ) {
    }

    public record OrderItemRequest(
            Long menuItemId,
            String name,
            BigDecimal unitPrice,
            Integer quantity
    ) {
    }

    /** Response body returned by commerce for a created order. */
    public record OrderResponse(
            Long id,
            Long customerId,
            Long restaurantId,
            String status,
            BigDecimal totalAmount,
            List<OrderItemDto> items
    ) {
    }

    public record OrderItemDto(
            Long menuItemId,
            String name,
            BigDecimal unitPrice,
            int quantity
    ) {
    }
}
