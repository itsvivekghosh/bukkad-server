package com.bhukkad.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class BusinessEventLogger {

    private static final Logger log = LoggerFactory.getLogger(BusinessEventLogger.class);
    private static final Logger orderLogger = LoggerFactory.getLogger(LoggingConstants.ORDER_LOGGER);
    private static final Logger paymentLogger = LoggerFactory.getLogger(LoggingConstants.PAYMENT_LOGGER);

    // ==================== ORDER EVENTS ====================

    public void logOrderCreated(Long orderId, String orderNumber, Long customerId,
                                Long restaurantId, Double totalAmount) {
        orderLogger.info(
                "[{}] [{}] OrderId: {} | OrderNumber: {} | CustomerId: {} | RestaurantId: {} | Amount: ₹{} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_ORDER_CREATED,
                orderId, orderNumber, customerId, restaurantId, totalAmount,
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logOrderStatusUpdate(Long orderId, String oldStatus, String newStatus) {
        orderLogger.info(
                "[{}] [{}] OrderId: {} | {} -> {} | UserId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_ORDER_UPDATED,
                orderId, oldStatus, newStatus,
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logOrderCancelled(Long orderId, String reason) {
        orderLogger.warn(
                "[{}] [{}] OrderId: {} | Reason: {} | UserId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_ORDER_CANCELLED,
                orderId, reason,
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logOrderDelivered(Long orderId, Long deliveryAgentId) {
        orderLogger.info(
                "[{}] [{}] OrderId: {} | AgentId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_ORDER_DELIVERED,
                orderId, deliveryAgentId,
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    // ==================== PAYMENT EVENTS ====================

    public void logPaymentInitiated(Long orderId, Double amount, String paymentMethod) {
        paymentLogger.info(
                "[{}] [{}] OrderId: {} | Amount: ₹{} | Method: {} | UserId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_PAYMENT_INITIATED,
                orderId, amount, paymentMethod,
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logPaymentSuccess(Long orderId, String transactionId, Double amount) {
        paymentLogger.info(
                "[{}] [{}] OrderId: {} | TxnId: {} | Amount: ₹{} | UserId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_PAYMENT_SUCCESS,
                orderId, transactionId, amount,
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logPaymentFailed(Long orderId, Double amount, String reason) {
        paymentLogger.error(
                "[{}] [{}] OrderId: {} | Amount: ₹{} | Reason: {} | UserId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_PAYMENT_FAILED,
                orderId, amount, reason,
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logPaymentRefunded(Long orderId, String transactionId, Double amount) {
        paymentLogger.info(
                "[{}] [{}] OrderId: {} | TxnId: {} | Refund: ₹{} | UserId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_PAYMENT_REFUNDED,
                orderId, transactionId, amount,
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    // ==================== RESTAURANT EVENTS ====================

    public void logRestaurantCreated(Long restaurantId, String name, Long ownerId) {
        log.info("[{}] [{}] RestaurantId: {} | Name: {} | OwnerId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_RESTAURANT_CREATED,
                restaurantId, name, ownerId,
                MDC.get(LoggingConstants.TRACE_ID));
    }

    public void logMenuItemCreated(Long itemId, String name, Long restaurantId, Double price) {
        log.info("[{}] [{}] ItemId: {} | Name: {} | RestaurantId: {} | Price: ₹{} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_MENU_ITEM_CREATED,
                itemId, name, restaurantId, price,
                MDC.get(LoggingConstants.TRACE_ID));
    }

    public void logCouponApplied(String couponCode, Long customerId, Double discount) {
        log.info("[{}] [{}] Code: {} | CustomerId: {} | Discount: ₹{} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_COUPON_APPLIED,
                couponCode, customerId, discount,
                MDC.get(LoggingConstants.TRACE_ID));
    }

    public void logReviewSubmitted(Long reviewId, Long restaurantId, Integer rating) {
        log.info("[{}] [{}] ReviewId: {} | RestaurantId: {} | Rating: {}/5 | UserId: {} | TraceId: {}",
                Instant.now(), LoggingConstants.EVENT_REVIEW_SUBMITTED,
                reviewId, restaurantId, rating,
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.TRACE_ID));
    }
}