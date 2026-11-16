package com.bhukkad.common.logging;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Bridges the application's MDC-based trace context with OpenTelemetry.
 * <p>
 * In local Floci development, OpenTelemetry is not available, so this class
 * is a no-op stub. In production, when OpenTelemetry is available, this would
 * bridge MDC trace/span ids to OpenTelemetry spans.
 */
public final class TracingBridge {

    private static final Logger log = LoggerFactory.getLogger(TracingBridge.class);

    private TracingBridge() {
    }

    /**
     * Returns a tracer instance for OpenTelemetry tracing.
     */
    public static io.opentelemetry.api.trace.Tracer tracer() {
        return GlobalOpenTelemetry.getTracer("bhukkad");
    }

    /**
     * Attaches MDC trace/span ids to the current OpenTelemetry span.
     * Safe to call unconditionally; does nothing when no span is recording.
     */
    public static void attachMdcIdsToCurrentSpan() {
        Span span = Span.current();
        if (span != null && span.isRecording()) {
            String traceId = MDC.get(LoggingConstants.TRACE_ID);
            String spanId = MDC.get(LoggingConstants.SPAN_ID);
            if (traceId != null) {
                span.setAttribute("log.traceId", traceId);
            }
            if (spanId != null) {
                span.setAttribute("log.spanId", spanId);
            }
        }
    }

    /**
     * No-op: opens a child span for background/asynchronous work.
     * Returns a no-op AutoCloseable.
     */
    public static AutoCloseable startSpan(String spanName) {
        return () -> {
        };
    }
}