package com.bhukkad.common.tracing;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Lightweight MDC-based trace context (plan §9: W3C traceparent across Kafka,
 * JSON logs with traceId/requestId/service).
 *
 * <p>Provides the same {@code traceId}/{@code spanId}/{@code requestId} MDC keys
 * the monolith's {@code com.bhukkad.logging.TraceContext} uses, so services
 * that share log aggregation produce uniform fields. This is a re-implemented
 * platform primitive (no dependency on the monolith), kept intentionally small.</p>
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String REQUEST_ID = "requestId";

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    public static String currentSpanId() {
        return MDC.get(SPAN_ID);
    }

    public static String currentRequestId() {
        return MDC.get(REQUEST_ID);
    }

    public static void putTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    public static void putRequestId(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(REQUEST_ID, requestId);
        }
    }

    public static void putSpanId(String spanId) {
        if (spanId != null && !spanId.isBlank()) {
            MDC.put(SPAN_ID, spanId);
        }
    }

    /** Seeds the MDC with a fresh trace id, returning a handle to clear it. */
    public static AutoCloseable startNew() {
        String previous = MDC.get(TRACE_ID);
        String traceId = generateTraceId();
        MDC.put(TRACE_ID, traceId);
        MDC.put(REQUEST_ID, traceId);
        return () -> {
            if (previous == null) {
                MDC.remove(TRACE_ID);
                MDC.remove(REQUEST_ID);
            } else {
                MDC.put(TRACE_ID, previous);
            }
        };
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Generates a 16-hex span id, matching the monolith's newSpanId(). */
    public static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
