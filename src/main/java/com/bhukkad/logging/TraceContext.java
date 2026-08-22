package com.bhukkad.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central access to request-scoped trace identifiers stored in SLF4J MDC.
 */
public final class TraceContext {

    private TraceContext() {
    }

    public static String getTraceId() {
        return MDC.get(LoggingConstants.TRACE_ID);
    }

    public static String getSpanId() {
        return MDC.get(LoggingConstants.SPAN_ID);
    }

    public static String getRequestId() {
        return MDC.get(LoggingConstants.REQUEST_ID);
    }

    /**
     * Generates a W3C trace-context-compatible span id (exactly 16 hex chars).
     * Used by the request filter to seed MDC so every log line in the
     * request can be correlated to a span.
     */
    public static String newSpanId() {
        return String.format("%016x", UUID.randomUUID().getMostSignificantBits() & 0xffffffffffffffffL);
    }

    public static Map<String, String> current() {
        Map<String, String> context = new LinkedHashMap<>();
        putIfPresent(context, LoggingConstants.TRACE_ID, getTraceId());
        putIfPresent(context, LoggingConstants.SPAN_ID, getSpanId());
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

    /**
     * Wraps a Runnable so that every log emitted during its execution carries
     * a synthetic traceId. Used for @Scheduled jobs (which run on scheduler
     * threads that have no MDC populated by the request filter) so that SQL
     * traces and DEBUG output emitted during background work remain
     * correlatable.
     *
     * <p>The synthetic traceId takes the form {@code sched-<short>-<seq>}; the
     * short suffix is a per-job counter so a single run is grep-able but two
     * consecutive runs of the same job are distinguishable.
     */
    public static Runnable wrapWithJobMdc(Runnable delegate) {
        return new MdcWrappedRunnable(delegate, nextShortSuffix());
    }

    /**
     * Variant of {@link #wrapWithJobMdc(Runnable)} that includes a job name in
     * the trace ID for easier debugging.
     */
    public static Runnable wrapWithJobMdc(String jobName, Runnable delegate) {
        return new MdcWrappedRunnable(delegate, jobName + "-" + nextShortSuffix());
    }

    private static final AtomicLong SCHED_SEQ = new AtomicLong();

    private static String nextShortSuffix() {
        return Long.toHexString(SCHED_SEQ.incrementAndGet());
    }

    private static final class MdcWrappedRunnable implements Runnable {
        private final Runnable delegate;
        private final String traceId;

        MdcWrappedRunnable(Runnable delegate, String traceId) {
            this.delegate = delegate;
            this.traceId = "sched-" + traceId;
        }

        @Override
        public void run() {
            Map<String, String> previous = copy();
            try {
                MDC.put(LoggingConstants.TRACE_ID, traceId);
                MDC.put(LoggingConstants.REQUEST_ID, traceId);
                MDC.put(LoggingConstants.REQUEST_METHOD, "SCHEDULED");
                MDC.put(LoggingConstants.REQUEST_PATH, "/__scheduled__");
                delegate.run();
            } finally {
                restore(previous);
            }
        }

        @Override
        public String toString() {
            return "MdcWrappedRunnable[" + traceId + "]";
        }
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
