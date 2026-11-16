package com.bhukkad.engagement.chat.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool interface for looking up order details.
 */
public interface OrderLookupTool {

    /**
     * Looks up an order by ID for a specific customer.
     *
     * @param customerId the customer ID
     * @param orderId    the order ID
     * @return order details or empty if not found/not owned
     */
    Optional<OrderDetails> lookupOrder(Long customerId, Long orderId);

    /**
     * Order details returned by lookup.
     */
    record OrderDetails(
            Long orderId,
            String orderNumber,
            String status,
            String restaurantName,
            List<OrderItem> items,
            Double totalAmount,
            String paymentStatus,
            String createdAt
    ) {}

    record OrderItem(String name, int quantity, Double price) {}
}