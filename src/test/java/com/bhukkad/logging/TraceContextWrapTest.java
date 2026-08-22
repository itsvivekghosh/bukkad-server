package com.bhukkad.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextWrapTest {

    @BeforeEach
    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test void wrapWithJobMdc_setsTraceAndRequestContext() {
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenReq = new AtomicReference<>();
        AtomicReference<String> seenMethod = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();

        Runnable wrapped = TraceContext.wrapWithJobMdc(() -> {
            seenTrace.set(MDC.get(LoggingConstants.TRACE_ID));
            seenReq.set(MDC.get(LoggingConstants.REQUEST_ID));
            seenMethod.set(MDC.get(LoggingConstants.REQUEST_METHOD));
            seenPath.set(MDC.get(LoggingConstants.REQUEST_PATH));
        });
        wrapped.run();

        assertTrue(seenTrace.get().startsWith("sched-"));
        assertEquals(seenTrace.get(), seenReq.get());
        assertEquals("SCHEDULED", seenMethod.get());
        assertEquals("/__scheduled__", seenPath.get());
        // MDC must be restored after the runnable completes.
        assertNull(MDC.get(LoggingConstants.TRACE_ID));
    }

    @Test void wrapWithJobMdc_namedJob_setsName() {
        AtomicReference<String> trace = new AtomicReference<>();
        Runnable wrapped = TraceContext.wrapWithJobMdc("ORDER-SYNC", () ->
                trace.set(MDC.get(LoggingConstants.TRACE_ID)));
        wrapped.run();
        assertTrue(trace.get().startsWith("sched-ORDER-SYNC-"));
    }

    @Test void wrapWithJobMdc_restoresPreviousMdc() {
        MDC.put(LoggingConstants.TRACE_ID, "outer-trace");
        Runnable wrapped = TraceContext.wrapWithJobMdc(() -> {
            // inside: trace replaced with sched-...
            assertTrue(MDC.get(LoggingConstants.TRACE_ID).startsWith("sched-"));
        });
        wrapped.run();
        // After: previous context restored.
        assertEquals("outer-trace", MDC.get(LoggingConstants.TRACE_ID));
    }

    @Test void wrapWithJobMdc_restoresEvenWhenDelegateThrows() {
        Runnable wrapped = TraceContext.wrapWithJobMdc(() -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(IllegalStateException.class, wrapped::run);
        assertNull(MDC.get(LoggingConstants.TRACE_ID));
    }

    @Test void wrapWithJobMdc_hasDescriptiveToString() {
        Runnable wrapped = TraceContext.wrapWithJobMdc(() -> {});
        assertTrue(wrapped.toString().startsWith("MdcWrappedRunnable[sched-"));
    }

    @Test void mdcTaskDecorator_propagatesMdcAndSecurityContext() {
        MDC.put(LoggingConstants.TRACE_ID, "decorator-trace");
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<org.springframework.security.core.context.SecurityContext> ctx = new AtomicReference<>();

        MdcTaskDecorator decorator = new MdcTaskDecorator();
        Runnable decorated = decorator.decorate(() -> {
            ran.set(true);
            seen.set(MDC.get(LoggingConstants.TRACE_ID));
            ctx.set(org.springframework.security.core.context.SecurityContextHolder.getContext());
        });
        decorated.run();

        assertTrue(ran.get());
        assertEquals("decorator-trace", seen.get());
        assertNull(MDC.get(LoggingConstants.TRACE_ID));
        // Security context cleared after run.
        assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }
}
