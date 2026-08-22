package com.bhukkad.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    @BeforeEach
    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void newSpanId_returns16HexChars() {
        String spanId = TraceContext.newSpanId();
        assertEquals(16, spanId.length());
        assertTrue(spanId.matches("[0-9a-f]{16}"));
    }

    @Test
    void newSpanId_isUniquePerCall() {
        assertTrue(!TraceContext.newSpanId().equals(TraceContext.newSpanId()));
    }

    @Test
    void getSpanId_readsMdc() {
        MDC.put(LoggingConstants.SPAN_ID, "0123456789abcdef");
        assertEquals("0123456789abcdef", TraceContext.getSpanId());
    }

    @Test
    void current_includesSpanIdWhenPresent() {
        MDC.put(LoggingConstants.TRACE_ID, "trace-1");
        MDC.put(LoggingConstants.SPAN_ID, "0123456789abcdef");
        MDC.put(LoggingConstants.REQUEST_ID, "req-1");

        Map<String, String> context = TraceContext.current();

        assertEquals("trace-1", context.get(LoggingConstants.TRACE_ID));
        assertEquals("0123456789abcdef", context.get(LoggingConstants.SPAN_ID));
        assertEquals("req-1", context.get(LoggingConstants.REQUEST_ID));
    }
}
