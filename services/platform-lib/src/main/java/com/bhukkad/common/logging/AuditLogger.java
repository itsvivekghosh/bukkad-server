package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Centralized audit logging for sensitive operations (payments, refunds,
 * auth, admin actions). Uses a dedicated logger so audit events can be
 * routed to a separate index/pipeline without affecting application logs.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(LoggingConstants.SECURITY_LOGGER);
    private final LogMetricsEmitter metricsEmitter;

    public AuditLogger(LogMetricsEmitter metricsEmitter) {
        this.metricsEmitter = metricsEmitter;
    }

    /**
     * Logs a payment event (initiated/success/failed/refunded).
     */
    public void logPayment(String event, String userId, String orderId, BigDecimal amount, String currency, String status, Map<String, Object> extra) {
        if (log.isInfoEnabled()) {
            log.info("AUDIT | type=PAYMENT | event={} | userId={} | orderId={} | amount={} | currency={} | status={} | traceId={} | extra={}",
                event, userId, orderId, amount, currency, status, TraceContext.currentTraceId(), toCompactString(extra));
            metricsEmitter.recordAuditEvent();
        }
    }

    /**
     * Logs a refund event.
     */
    public void logRefund(String adminId, String orderId, BigDecimal amount, String reason, String status) {
        if (log.isInfoEnabled()) {
            log.info("AUDIT | type=REFUND | event=REFUND_INITIATED | adminId={} | orderId={} | amount={} | reason={} | status={} | traceId={}",
                adminId, orderId, amount, reason, status, TraceContext.currentTraceId());
            metricsEmitter.recordAuditEvent();
        }
    }

    /**
     * Logs an authentication event (login/logout/failure).
     */
    public void logAuth(String event, String userId, String ipAddress, String userAgent, boolean success) {
        if (log.isInfoEnabled()) {
            log.info("AUDIT | type=AUTH | event={} | userId={} | ipAddress={} | userAgent={} | success={} | traceId={}",
                event, userId, maskIp(ipAddress), maskUserAgent(userAgent), success, TraceContext.currentTraceId());
            metricsEmitter.recordAuditEvent();
        }
    }

    /**
     * Logs an authorization failure (access denied, invalid token).
     */
    public void logAuthorization(String event, String userId, String path, String reason) {
        if (log.isWarnEnabled()) {
            log.warn("AUDIT | type=AUTHZ | event={} | userId={} | path={} | reason={} | traceId={}",
                event, userId, path, reason, TraceContext.currentTraceId());
            metricsEmitter.recordAuditEvent();
        }
    }

    /**
     * Logs an admin action (config change, user role change, etc.).
     */
    public void logAdminAction(String adminId, String action, String targetType, String targetId, Map<String, Object> changes) {
        if (log.isInfoEnabled()) {
            log.info("AUDIT | type=ADMIN | event=ADMIN_ACTION | adminId={} | action={} | targetType={} | targetId={} | changes={} | traceId={}",
                adminId, action, targetType, targetId, toCompactString(changes), TraceContext.currentTraceId());
            metricsEmitter.recordAuditEvent();
        }
    }

    /**
     * Logs a data export or privacy event.
     */
    public void logDataEvent(String userId, String event, String dataType, Long recordsAffected) {
        if (log.isInfoEnabled()) {
            log.info("AUDIT | type=DATA | event={} | userId={} | dataType={} | recordsAffected={} | traceId={}",
                event, userId, dataType, recordsAffected, TraceContext.currentTraceId());
            metricsEmitter.recordAuditEvent();
        }
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        // Mask last octet for IPv4
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".xxx";
            }
        }
        return ip;
    }

    private String maskUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        if (userAgent.length() > 100) {
            return userAgent.substring(0, 100) + "...";
        }
        return userAgent;
    }

    private String toCompactString(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        // Compact representation: avoid huge nested objects in audit logs
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            Object value = entry.getValue();
            String valueStr = value != null ? value.toString() : "null";
            if (valueStr.length() > 200) {
                valueStr = valueStr.substring(0, 200) + "...";
            }
            sb.append(entry.getKey()).append("=").append(valueStr);
        }
        sb.append("}");
        return sb.toString();
    }
}
