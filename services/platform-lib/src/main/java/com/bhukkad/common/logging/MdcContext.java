package com.bhukkad.common.logging;

import org.slf4j.MDC;

import java.util.Map;

/**
 * AutoCloseable MDC scope (port of {@code com.bhukkad.logging.MDCContext}):
 * saves the current MDC, applies the given context, and restores on close —
 * safe for both sync (try-with-resources) and async usage.
 */
public final class MdcContext implements AutoCloseable {

    private final Map<String, String> previous;

    private MdcContext(Map<String, String> context) {
        this.previous = MDC.getCopyOfContextMap();
        if (context != null) {
            MDC.setContextMap(context);
        }
    }

    public static MdcContext with(Map<String, String> context) {
        return new MdcContext(context);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
