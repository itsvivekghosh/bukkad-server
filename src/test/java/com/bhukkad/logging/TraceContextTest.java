package com.bhukkad.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void current_returnsPopulatedContext() {
        MDC.put(LoggingConstants.TRACE_ID, "trace123");
        MDC.put(LoggingConstants.REQUEST_ID, "req456");
        MDC.put(LoggingConstants.USER_ID, "7");

        var context = TraceContext.current();

        assertEquals("trace123", context.get(LoggingConstants.TRACE_ID));
        assertEquals("req456", context.get(LoggingConstants.REQUEST_ID));
        assertEquals("7", context.get(LoggingConstants.USER_ID));
    }

    @Test
    void copyAndRestore_preservesMdcAcrossThreads() {
        MDC.put(LoggingConstants.TRACE_ID, "trace-copy");
        MDC.put(LoggingConstants.REQUEST_ID, "req-copy");

        var copy = TraceContext.copy();
        TraceContext.clear();
        assertNull(TraceContext.getTraceId());

        TraceContext.restore(copy);
        assertEquals("trace-copy", TraceContext.getTraceId());
        assertEquals("req-copy", TraceContext.getRequestId());
    }

    @Test
    void clear_removesAllMdcValues() {
        MDC.put(LoggingConstants.TRACE_ID, "x");
        TraceContext.clear();
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }
}
