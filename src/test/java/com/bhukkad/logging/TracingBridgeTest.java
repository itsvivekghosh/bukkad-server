package com.bhukkad.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TracingBridge}.
 *
 * <p>The bridge is designed to be a safe no-op when OpenTelemetry is not
 * configured (the default), which is what these tests exercise — they must
 * never throw even when {@code GlobalOpenTelemetry} resolves to the no-op
 * tracer.</p>
 */
class TracingBridgeTest {

    @Test
    void attachMdcIdsToCurrentSpan_noOp_whenNoSpan() {
        MDC.put(LoggingConstants.TRACE_ID, "abc");
        MDC.put(LoggingConstants.SPAN_ID, "def");

        assertDoesNotThrow(TracingBridge::attachMdcIdsToCurrentSpan);
    }

    @Test
    void attachMdcIdsToCurrentSpan_noOp_whenMdcEmpty() {
        MDC.remove(LoggingConstants.TRACE_ID);
        MDC.remove(LoggingConstants.SPAN_ID);

        assertDoesNotThrow(TracingBridge::attachMdcIdsToCurrentSpan);
    }

    @Test
    void startSpan_returnsCloseableThatCanBeClosed() throws Exception {
        try (AutoCloseable span = TracingBridge.startSpan("test-span")) {
            assertTrue(span != null);
        }
        assertDoesNotThrow(() -> {
        });
    }

    @Test
    void tracer_isNeverNull_evenWhenTracingDisabled() {
        assertTrue(TracingBridge.tracer() != null);
    }
}
