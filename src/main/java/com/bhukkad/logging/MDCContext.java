package com.bhukkad.logging;

import org.slf4j.MDC;

import java.util.UUID;

public class MDCContext implements AutoCloseable {

    private final String traceId;

    private MDCContext(String traceId) {
        this.traceId = traceId;
    }

    public static MDCContext create() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(LoggingConstants.TRACE_ID, traceId);
        return new MDCContext(traceId);
    }

    public static MDCContext create(String traceId) {
        MDC.put(LoggingConstants.TRACE_ID, traceId);
        return new MDCContext(traceId);
    }

    public MDCContext withUserId(String userId) {
        MDC.put(LoggingConstants.USER_ID, userId);
        return this;
    }

    public MDCContext withUserEmail(String email) {
        MDC.put(LoggingConstants.USER_EMAIL, email);
        return this;
    }

    public MDCContext withUserRole(String role) {
        MDC.put(LoggingConstants.USER_ROLE, role);
        return this;
    }

    public MDCContext withRequestPath(String path) {
        MDC.put(LoggingConstants.REQUEST_PATH, path);
        return this;
    }

    public MDCContext withRequestMethod(String method) {
        MDC.put(LoggingConstants.REQUEST_METHOD, method);
        return this;
    }

    public MDCContext withIpAddress(String ipAddress) {
        MDC.put(LoggingConstants.IP_ADDRESS, ipAddress);
        return this;
    }

    public MDCContext withSessionId(String sessionId) {
        MDC.put(LoggingConstants.SESSION_ID, sessionId);
        return this;
    }

    public String getTraceId() {
        return traceId;
    }

    @Override
    public void close() {
        MDC.clear();
    }
}