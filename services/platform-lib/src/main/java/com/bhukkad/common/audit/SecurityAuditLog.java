package com.bhukkad.common.audit;

import com.bhukkad.common.logging.LoggingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Component
public class SecurityAuditLog {

    private static final Logger auditLogger =
            LoggerFactory.getLogger(LoggingConstants.SECURITY_LOGGER);

    public void logAuthLogin(Long userId, String email, String role, String outcome) {
        auditLogger.info(
                "[{}] [{}] [{}] userId={} email={} role={} traceId={}",
                Instant.now(),
                LoggingConstants.EVENT_USER_LOGIN,
                outcome,
                userId,
                mask(email),
                role,
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logAuthLogout(Long userId, String email) {
        auditLogger.info(
                "[{}] [{}] userId={} email={} traceId={}",
                Instant.now(),
                LoggingConstants.EVENT_USER_LOGOUT,
                userId,
                mask(email),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logPaymentEvent(Long userId, String paymentId, String purpose,
                                BigDecimal amount, String status, String paymentMethod) {
        auditLogger.info(
                "[{}] [PAYMENT_{}] userId={} paymentId={} purpose={} amount={} method={} traceId={}",
                Instant.now(),
                status,
                userId,
                paymentId,
                purpose,
                amount,
                paymentMethod,
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logPaymentRefund(Long userId, String paymentId, BigDecimal amount, String reason) {
        auditLogger.warn(
                "[{}] [PAYMENT_REFUND] userId={} paymentId={} amount={} reason={} traceId={}",
                Instant.now(),
                userId,
                paymentId,
                amount,
                reason,
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logOrderEvent(Long userId, Long orderId, String eventType, String restaurantId) {
        auditLogger.info(
                "[{}] [{}] userId={} orderId={} restaurantId={} traceId={}",
                Instant.now(),
                eventType,
                userId,
                orderId,
                restaurantId,
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logOrderCancel(Long userId, Long orderId, String reason, String restaurantId) {
        auditLogger.warn(
                "[{}] [ORDER_CANCELLED] userId={} orderId={} reason={} restaurantId={} traceId={}",
                Instant.now(),
                userId,
                orderId,
                reason,
                restaurantId,
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    private static String mask(String value) {
        if (value == null) return "null";
        if (value.length() <= 2) return "***";
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }
}
