package com.bhukkad.common.tracing;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @Test
    void startNew_populatesTraceAndRequestId() throws Exception {
        try (AutoCloseable ignored = TraceContext.startNew()) {
            assertThat(TraceContext.currentTraceId()).hasSize(32);
            assertThat(TraceContext.currentRequestId()).isEqualTo(TraceContext.currentTraceId());
        }
    }

    @Test
    void startNew_restoresPreviousTraceId() throws Exception {
        MDC.put(TraceContext.TRACE_ID, "previous-trace");
        try (AutoCloseable ignored = TraceContext.startNew()) {
            assertThat(TraceContext.currentTraceId()).isNotEqualTo("previous-trace");
        }
        assertThat(TraceContext.currentTraceId()).isEqualTo("previous-trace");
        MDC.remove(TraceContext.TRACE_ID);
    }

    @Test
    void putTraceId_ignoresBlank() {
        TraceContext.putTraceId(null);
        TraceContext.putTraceId("  ");
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void putTraceId_setsValue() {
        TraceContext.putTraceId("abc123");
        assertThat(TraceContext.currentTraceId()).isEqualTo("abc123");
        MDC.remove(TraceContext.TRACE_ID);
    }

    @Test
    void newSpanId_is16Hex() {
        assertThat(TraceContext.newSpanId()).matches("[0-9a-f]{16}");
    }

    @Test
    void generateTraceId_is32Hex() {
        assertThat(TraceContext.generateTraceId()).matches("[0-9a-f]{32}");
    }
}
