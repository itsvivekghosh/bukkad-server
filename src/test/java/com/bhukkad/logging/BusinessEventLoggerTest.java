package com.bhukkad.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BusinessEventLoggerTest {

    private final BusinessEventLogger logger = new BusinessEventLogger();

    @Test
    void allEvents_logWithoutError() {
        assertDoesNotThrow(() -> {
            logger.logOrderCreated(1L, "ORD-1", 2L, 3L, 250.0);
            logger.logOrderStatusUpdate(1L, "PLACED", "CONFIRMED");
            logger.logOrderCancelled(1L, "changed mind");
            logger.logOrderDelivered(1L, 9L);
            logger.logPaymentInitiated(1L, 250.0, "UPI");
            logger.logPaymentSuccess(1L, "TXN-1", 250.0);
            logger.logPaymentFailed(1L, 250.0, "declined");
            logger.logPaymentRefunded(1L, "TXN-1", 250.0);
            logger.logRestaurantCreated(3L, "Spice Hub", 4L);
            logger.logMenuItemCreated(5L, "Biryani", 3L, 200.0);
            logger.logCouponApplied("SAVE10", 2L, 20.0);
            logger.logReviewSubmitted(6L, 3L, 5);
        });
    }
}
