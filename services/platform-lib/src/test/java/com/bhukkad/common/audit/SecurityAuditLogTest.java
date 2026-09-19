package com.bhukkad.common.audit;

import com.bhukkad.common.logging.LoggingConstants;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAuditLogTest {

    @Test
    void logAuthLogin_doesNotThrow() {
        SecurityAuditLog auditLog = new SecurityAuditLog();
        MDC.put(LoggingConstants.TRACE_ID, "trace-1");
        try {
            auditLog.logAuthLogin(1L, "user@example.com", "CUSTOMER", "SUCCESS");
            auditLog.logAuthLogin(2L, "admin@example.com", "ADMIN", "FAILED");
        } finally {
            MDC.remove(LoggingConstants.TRACE_ID);
        }
    }

    @Test
    void logAuthLogout_doesNotThrow() {
        SecurityAuditLog auditLog = new SecurityAuditLog();
        MDC.put(LoggingConstants.TRACE_ID, "trace-2");
        try {
            auditLog.logAuthLogout(1L, "user@example.com");
        } finally {
            MDC.remove(LoggingConstants.TRACE_ID);
        }
    }

    @Test
    void logPaymentEvent_doesNotThrow() {
        SecurityAuditLog auditLog = new SecurityAuditLog();
        MDC.put(LoggingConstants.TRACE_ID, "trace-3");
        try {
            auditLog.logPaymentEvent(1L, "pay-123", "ORDER", new BigDecimal("250.00"), "SUCCESS", "UPI");
        } finally {
            MDC.remove(LoggingConstants.TRACE_ID);
        }
    }

    @Test
    void logPaymentRefund_doesNotThrow() {
        SecurityAuditLog auditLog = new SecurityAuditLog();
        MDC.put(LoggingConstants.TRACE_ID, "trace-4");
        try {
            auditLog.logPaymentRefund(1L, "pay-123", new BigDecimal("250.00"), "customer-request");
        } finally {
            MDC.remove(LoggingConstants.TRACE_ID);
        }
    }

    @Test
    void logOrderEvent_doesNotThrow() {
        SecurityAuditLog auditLog = new SecurityAuditLog();
        MDC.put(LoggingConstants.TRACE_ID, "trace-5");
        try {
            auditLog.logOrderEvent(1L, 100L, "ORDER_CREATED", "rest-1");
            auditLog.logOrderEvent(1L, 100L, "ORDER_DELIVERED", "rest-1");
        } finally {
            MDC.remove(LoggingConstants.TRACE_ID);
        }
    }

    @Test
    void logOrderCancel_doesNotThrow() {
        SecurityAuditLog auditLog = new SecurityAuditLog();
        MDC.put(LoggingConstants.TRACE_ID, "trace-6");
        try {
            auditLog.logOrderCancel(1L, 100L, "user-cancelled", "rest-1");
        } finally {
            MDC.remove(LoggingConstants.TRACE_ID);
        }
    }

    @Test
    void logAuthLogin_handlesNullEmail() {
        SecurityAuditLog auditLog = new SecurityAuditLog();
        MDC.put(LoggingConstants.TRACE_ID, "trace-7");
        try {
            auditLog.logAuthLogin(null, null, "CUSTOMER", "SUCCESS");
        } finally {
            MDC.remove(LoggingConstants.TRACE_ID);
        }
    }
}
