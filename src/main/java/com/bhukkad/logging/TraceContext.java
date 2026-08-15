package com.bhukkad.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central access to request-scoped trace identifiers stored in SLF4J MDC.
 */
public final class TraceContext {

    private TraceContext() {
    }

    public static String getTraceId() {
        return MDC.get(LoggingConstants.TRACE_ID);
    }

    public static String getRequestId() {
        return MDC.get(LoggingConstants.REQUEST_ID);
    }

    public static Map<String, String> current() {
        Map<String, String> context = new LinkedHashMap<>();
        putIfPresent(context, LoggingConstants.TRACE_ID, getTraceId());
        putIfPresent(context, LoggingConstants.REQUEST_ID, getRequestId());
        putIfPresent(context, LoggingConstants.USER_ID, MDC.get(LoggingConstants.USER_ID));
        putIfPresent(context, LoggingConstants.REQUEST_PATH, MDC.get(LoggingConstants.REQUEST_PATH));
        putIfPresent(context, LoggingConstants.REQUEST_METHOD, MDC.get(LoggingConstants.REQUEST_METHOD));
        putIfPresent(context, LoggingConstants.IP_ADDRESS, MDC.get(LoggingConstants.IP_ADDRESS));
        return context;
    }

    public static Map<String, String> copy() {
        Map<String, String> copy = new LinkedHashMap<>();
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        if (contextMap != null) {
            copy.putAll(contextMap);
        }
        return copy;
    }

    public static void restore(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(context);
    }

    public static void clear() {
        MDC.clear();
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
