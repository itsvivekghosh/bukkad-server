package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import com.bhukkad.common.tracing.Traceparent;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the trace/request ids to seed the MDC for an incoming request
 * (port of {@code com.bhukkad.logging.TraceIdResolver}): honours an incoming
 * W3C traceparent first, then an {@code X-Request-Id} header, then generates.
 */
public final class TraceIdResolver {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private TraceIdResolver() {
    }

    /** Seeds MDC and returns the trace id that is now current. */
    public static String seedFrom(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        Traceparent tp = Traceparent.parse(traceparent).orElse(null);
        String traceId = tp != null ? tp.traceId() : null;

        if (traceId == null) {
            String requestId = request.getHeader(REQUEST_ID_HEADER);
            traceId = (requestId != null && !requestId.isBlank()) ? requestId : TraceContext.generateTraceId();
        }
        TraceContext.putTraceId(traceId);
        TraceContext.putRequestId(traceId);
        return traceId;
    }

    public static String seedNew() {
        String traceId = TraceContext.generateTraceId();
        TraceContext.putTraceId(traceId);
        TraceContext.putRequestId(traceId);
        return traceId;
    }
}
