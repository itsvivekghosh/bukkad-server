package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingTest {

    @Test
    void mdcContext_restoresPreviousOnClose() {
        MDC.put("key", "before");
        try (MdcContext ignored = MdcContext.with(java.util.Map.of("key", "inside"))) {
            assertThat(MDC.get("key")).isEqualTo("inside");
        }
        assertThat(MDC.get("key")).isEqualTo("before");
        MDC.remove("key");
    }

    @Test
    void mdcTaskDecorator_propagatesContext() throws Exception {
        MDC.put(TraceContext.TRACE_ID, "trace-abc");
        java.util.concurrent.Callable<String> task = MdcTaskDecorator.decorate(
                () -> MDC.get(TraceContext.TRACE_ID));
        assertThat(task.call()).isEqualTo("trace-abc");
        MDC.remove(TraceContext.TRACE_ID);
    }

    @Test
    void mdcTaskDecorator_runnableRestoresAfterRun() {
        MDC.put(TraceContext.TRACE_ID, "outer");
        java.util.concurrent.atomic.AtomicReference<String> inside = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = MdcTaskDecorator.decorate(() -> inside.set(MDC.get(TraceContext.TRACE_ID)));
        task.run();
        assertThat(inside.get()).isEqualTo("outer");
        MDC.remove(TraceContext.TRACE_ID);
    }

    @Test
    void traceIdResolver_generatesWhenNoHeaders() {
        jakarta.servlet.http.HttpServletRequest request = mockRequest(null, null);
        String traceId = TraceIdResolver.seedFrom(request);
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(TraceContext.currentTraceId()).isEqualTo(traceId);
        MDC.remove(TraceContext.TRACE_ID);
        MDC.remove(TraceContext.REQUEST_ID);
    }

    @Test
    void traceIdResolver_usesIncomingTraceparent() {
        jakarta.servlet.http.HttpServletRequest request = mockRequest(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null);
        String traceId = TraceIdResolver.seedFrom(request);
        assertThat(traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.remove(TraceContext.TRACE_ID);
        MDC.remove(TraceContext.REQUEST_ID);
    }

    @Test
    void traceIdResolver_usesRequestIdHeaderAsFallback() {
        jakarta.servlet.http.HttpServletRequest request = mockRequest(null, "req-123");
        String traceId = TraceIdResolver.seedFrom(request);
        assertThat(traceId).isEqualTo("req-123");
        MDC.remove(TraceContext.TRACE_ID);
        MDC.remove(TraceContext.REQUEST_ID);
    }

    @Test
    void logSanitizer_redactsSecrets() {
        assertThat(LogSanitizer.sanitize("password=super-secret")).contains("[REDACTED]");
        assertThat(LogSanitizer.sanitize("Authorization: Bearer abc123")).contains("[REDACTED]");
        assertThat(LogSanitizer.sanitize("card 4111111111111111 failed")).contains("[REDACTED]");
        assertThat(LogSanitizer.sanitize("plain order id 42")).isEqualTo("plain order id 42");
        assertThat(LogSanitizer.sanitize(null)).isNull();
    }

    private jakarta.servlet.http.HttpServletRequest mockRequest(String traceparent, String requestId) {
        jakarta.servlet.http.HttpServletRequest request =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getHeader("traceparent")).thenReturn(traceparent);
        org.mockito.Mockito.when(request.getHeader("X-Request-Id")).thenReturn(requestId);
        return request;
    }
}
