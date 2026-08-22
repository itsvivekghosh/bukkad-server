package com.bhukkad.logging;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the application's MDC-based trace context (see {@link TraceContext})
 * with OpenTelemetry so that, when distributed tracing is enabled
 * ({@code management.tracing.enabled=true}), spans carry the same trace/span ids
 * that appear in the structured logs.
 *
 * <p>When tracing is disabled, {@code GlobalOpenTelemetry} resolves to the
 * no-op tracer and every method is a cheap no-op — so this utility is safe to
 * call unconditionally from request-scoped code without measurable overhead.</p>
 */
public final class TracingBridge {

    private static final Logger log = LoggerFactory.getLogger(TracingBridge.class);

    private TracingBridge() {
    }

    /** The OpenTelemetry tracer; resolves to the no-op tracer when tracing is off. */
    public static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer("bhukkad-server");
    }

    /**
     * Attaches the current MDC trace/span ids to the active OpenTelemetry span
     * (if one exists). Call this after the request filter seeds MDC so a span
     * started by the OTel instrumentation carries the same ids as the log lines.
     */
    public static void attachMdcIdsToCurrentSpan() {
        try {
            String traceId = TraceContext.getTraceId();
            String spanId = TraceContext.getSpanId();
            if (traceId == null || spanId == null) {
                return;
            }
            Span current = Span.current();
            if (current == null || !current.isRecording()) {
                return;
            }
            current.setAttribute("log.traceId", traceId);
            current.setAttribute("log.spanId", spanId);
        } catch (Exception ex) {
            // Tracing is best-effort; never let instrumentation failures surface.
            log.trace("Tracing bridge skipped: {}", ex.getMessage());
        }
    }

    /**
     * Opens a child span for background/asynchronous work (e.g. outbox
     * processing, scheduled jobs). Returns an AutoCloseable that closes the
     * scope and ends the span; safe to use in try-with-resources.
     */
    public static AutoCloseable startSpan(String spanName) {
        Span span = tracer().spanBuilder(spanName).startSpan();
        Scope scope = span.makeCurrent();
        return () -> {
            scope.close();
            span.end();
        };
    }
}
