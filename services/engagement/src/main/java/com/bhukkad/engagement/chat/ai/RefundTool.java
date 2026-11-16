package com.bhukkad.engagement.chat.ai;

import java.util.Optional;

/**
 * Tool interface for processing refund requests.
 */
public interface RefundTool {

    /**
     * Initiates a refund request for an order.
     *
     * @param customerId the customer ID
     * @param orderId    the order ID
     * @param reason     reason for refund
     * @param amount     optional partial amount (null for full refund)
     * @return refund result
     */
    RefundResult requestRefund(Long customerId, Long orderId, String reason, Double amount);

    /**
     * Refund request result.
     */
    record RefundResult(
            boolean success,
            String refundId,
            String status,        // e.g., "PENDING", "APPROVED", "REJECTED", "PROCESSED"
            String message,
            Double amount,
            String estimatedProcessingTime
    ) {}
}