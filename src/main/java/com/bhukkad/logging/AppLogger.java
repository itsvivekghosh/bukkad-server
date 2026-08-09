package com.bhukkad.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

public class AppLogger {

    private final Logger logger;

    private AppLogger(Class<?> clazz) {
        this.logger = LoggerFactory.getLogger(clazz);
    }

    public static AppLogger getLogger(Class<?> clazz) {
        return new AppLogger(clazz);
    }

    // ==================== INFO METHODS ====================

    public void info(String message) {
        logger.info(message);
    }

    public void info(String message, Object... args) {
        logger.info(message, args);
    }

    public void logEvent(String event, String message) {
        logger.info("[EVENT: {}] {}", event, message);
    }

    public void logEvent(String event, String message, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[EVENT: ").append(event).append("] ").append(message);
        if (context != null && !context.isEmpty()) {
            sb.append(" | Context: ");
            context.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
        }
        logger.info(sb.toString());
    }

    // ==================== DEBUG METHODS ====================

    public void debug(String message) {
        if (logger.isDebugEnabled()) {
            logger.debug(message);
        }
    }

    public void debug(String message, Object... args) {
        if (logger.isDebugEnabled()) {
            logger.debug(message, args);
        }
    }

    // ==================== WARN METHODS ====================

    public void warn(String message) {
        logger.warn(message);
    }

    public void warn(String message, Object... args) {
        logger.warn(message, args);
    }

    public void warnWithContext(String message, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder(message);
        if (context != null && !context.isEmpty()) {
            sb.append(" | Context: ");
            context.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
        }
        logger.warn(sb.toString());
    }

    // ==================== ERROR METHODS ====================

    public void error(String message) {
        logger.error(message);
    }

    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }

    public void error(String message, Object... args) {
        logger.error(message, args);
    }

    public void errorWithContext(String message, Throwable throwable, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder(message);
        if (context != null && !context.isEmpty()) {
            sb.append(" | Context: ");
            context.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
        }
        logger.error(sb.toString(), throwable);
    }

    // ==================== PERFORMANCE METHODS ====================

    public void logPerformance(String operation, long durationMs) {
        Logger perfLogger = LoggerFactory.getLogger(LoggingConstants.PERFORMANCE_LOGGER);
        if (durationMs > 1000) {
            perfLogger.warn("[SLOW OPERATION] {} took {}ms", operation, durationMs);
        } else {
            perfLogger.info("[PERFORMANCE] {} took {}ms", operation, durationMs);
        }
    }

    // ==================== SECURITY METHODS ====================

    public void logSecurityEvent(String event, String details) {
        Logger secLogger = LoggerFactory.getLogger(LoggingConstants.SECURITY_LOGGER);
        secLogger.info("[SECURITY] [{}] {} | TraceId: {} | UserId: {} | IP: {}",
                event, details,
                MDC.get(LoggingConstants.TRACE_ID),
                MDC.get(LoggingConstants.USER_ID),
                MDC.get(LoggingConstants.IP_ADDRESS));
    }

    public void logSecurityWarning(String event, String details) {
        Logger secLogger = LoggerFactory.getLogger(LoggingConstants.SECURITY_LOGGER);
        secLogger.warn("[SECURITY WARNING] [{}] {} | TraceId: {} | IP: {}",
                event, details,
                MDC.get(LoggingConstants.TRACE_ID),
                MDC.get(LoggingConstants.IP_ADDRESS));
    }

    // ==================== BUSINESS METHODS ====================

    public void logOrderEvent(String event, Long orderId, String details) {
        Logger orderLogger = LoggerFactory.getLogger(LoggingConstants.ORDER_LOGGER);
        orderLogger.info("[ORDER] [{}] OrderId: {} | {} | TraceId: {} | UserId: {}",
                event, orderId, details,
                MDC.get(LoggingConstants.TRACE_ID),
                MDC.get(LoggingConstants.USER_ID));
    }

    public void logPaymentEvent(String event, Long orderId, Double amount, String details) {
        Logger paymentLogger = LoggerFactory.getLogger(LoggingConstants.PAYMENT_LOGGER);
        paymentLogger.info("[PAYMENT] [{}] OrderId: {} | Amount: {} | {} | TraceId: {}",
                event, orderId, amount, details,
                MDC.get(LoggingConstants.TRACE_ID));
    }

    // ==================== MDC METHODS ====================

    public void addContext(String key, String value) {
        MDC.put(key, value);
    }

    public void clearContext() {
        MDC.clear();
    }
}