package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Development implementation of RefundTool.
 * <p>
 * <b>DEV ONLY</b> — Returns mock refund result. In production, integrate with Payment Service.
 */
@Component
@Profile({"dev", "test", "local"})
public class DevRefundTool implements RefundTool {

    private static final Random RANDOM = new Random();

    @Override
    public RefundResult requestRefund(Long customerId, Long orderId, String reason, Double amount) {
        if (customerId == null || orderId == null) {
            return new RefundResult(false, null, "REJECTED", "Invalid customer or order ID", amount, null);
        }

        // Simulate refund eligibility check
        boolean eligible = RANDOM.nextDouble() > 0.1; // 90% eligible

        if (!eligible) {
            return new RefundResult(
                    false, null, "REJECTED",
                    "Refund not eligible for this order. Reason: " + reason,
                    amount, null
            );
        }

        String refundId = "REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String[] statuses = {"PENDING", "APPROVED", "PROCESSED"};
        String status = statuses[RANDOM.nextInt(statuses.length)];

        return new RefundResult(
                true, refundId, status,
                "Refund request submitted successfully. Reason: " + reason,
                amount != null ? amount : 25.00 + RANDOM.nextDouble() * 50,
                "3-5 business days"
        );
    }
}