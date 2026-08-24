package com.bhukkad.config;

import com.bhukkad.logging.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses a real single-thread scheduled executor as the delegate so every
 * delegation path is exercised end-to-end, including the MDC/trace-context
 * capture that must survive the hop onto the worker thread.
 */
class MdcPropagatingScheduledExecutorServiceTest {

    private ScheduledThreadPoolExecutor delegate;
    private MdcPropagatingScheduledExecutorService service;

    @BeforeEach
    void setUp() {
        delegate = new ScheduledThreadPoolExecutor(1);
        service = new MdcPropagatingScheduledExecutorService(delegate);
        MDC.put("traceId", "trace-1");
        MDC.put("requestId", "req-1");
    }

    @AfterEach
    void tearDown() {
        delegate.shutdownNow();
        MDC.clear();
    }

    @Test
    void execute_wrapsTaskWithFreshScheduledTraceId() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> traceId = new AtomicReference<>();
        service.execute(() -> {
            traceId.set(TraceContext.getTraceId());
            ran.countDown();
        });

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        // Scheduled jobs get their own sched-* trace id, not the caller's
        assertTrue(traceId.get().startsWith("sched-"), "got " + traceId.get());
    }

    @Test
    void submit_runnable_runsUnderScheduledTraceScope() throws Exception {
        AtomicReference<String> traceId = new AtomicReference<>();
        Future<?> future = service.submit(() ->
                traceId.set(TraceContext.getTraceId()));

        future.get(5, TimeUnit.SECONDS);
        assertTrue(traceId.get().startsWith("sched-"));
    }

    @Test
    void submit_runnableWithResult_returnsSuppliedResult() throws Exception {
        Future<String> future = service.submit(() -> TraceContext.getTraceId(), "fallback");

        assertEquals("fallback", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void submit_callable_propagatesCallerMdc() throws Exception {
        Future<String> future = service.submit(TraceContext::getTraceId);

        // Callables preserve the submitter's MDC context
        assertEquals("trace-1", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void schedule_delayedRunnable_runsUnderScheduledTraceScope() throws Exception {
        AtomicReference<String> traceId = new AtomicReference<>();
        ScheduledFuture<?> future = service.schedule(
                () -> traceId.set(TraceContext.getTraceId()), 10, TimeUnit.MILLISECONDS);

        future.get(5, TimeUnit.SECONDS);
        assertTrue(traceId.get().startsWith("sched-"));
    }

    @Test
    void schedule_delayedCallable_propagatesCallerMdc() throws Exception {
        ScheduledFuture<String> future = service.schedule(
                TraceContext::getTraceId, 10, TimeUnit.MILLISECONDS);

        assertEquals("trace-1", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void scheduleAtFixedRate_runsRepeatedly() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> future = service.scheduleAtFixedRate(
                runs::incrementAndGet, 0, 20, TimeUnit.MILLISECONDS);

        // Wait for at least two executions then cancel
        Thread.sleep(120);
        future.cancel(false);
        assertTrue(runs.get() >= 2);
    }

    @Test
    void scheduleWithFixedDelay_runsRepeatedly() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> future = service.scheduleWithFixedDelay(
                runs::incrementAndGet, 0, 20, TimeUnit.MILLISECONDS);

        Thread.sleep(120);
        future.cancel(false);
        assertTrue(runs.get() >= 2);
    }

    @Test
    void invokeAll_returnsCompletedFutures() throws Exception {
        List<Future<String>> futures = service.invokeAll(List.of(
                () -> "a", () -> "b"));

        assertEquals("a", futures.get(0).get(1, TimeUnit.SECONDS));
        assertEquals("b", futures.get(1).get(1, TimeUnit.SECONDS));
    }

    @Test
    void invokeAll_withTimeout_returnsCompletedFutures() throws Exception {
        List<Future<String>> futures = service.invokeAll(
                List.of(() -> "x"), 5, TimeUnit.SECONDS);

        assertEquals("x", futures.get(0).get(1, TimeUnit.SECONDS));
    }

    @Test
    void invokeAny_returnsFirstSuccessfulResult() throws Exception {
        String result = service.invokeAny(List.<Callable<String>>of(() -> "only"));

        assertEquals("only", result);
    }

    @Test
    void invokeAny_withTimeout_returnsResult() throws Exception {
        String result = service.invokeAny(
                List.<Callable<String>>of(() -> "y"), 5, TimeUnit.SECONDS);

        assertEquals("y", result);
    }

    @Test
    void shutdown_delegates() {
        service.shutdown();
        assertTrue(delegate.isShutdown());
    }

    @Test
    void shutdownNow_delegatesAndReturnsPendingTasks() {
        // Queue a long-running task that shutdownNow must interrupt
        service.execute(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        List<Runnable> pending = service.shutdownNow();

        assertTrue(delegate.isShutdown());
        assertNotNull(pending);
    }

    @Test
    void isShutdown_delegates() {
        assertFalse(service.isShutdown());
        service.shutdown();
        assertTrue(service.isShutdown());
    }

    @Test
    void isTerminated_delegates() {
        service.shutdown();
        // Single-thread pool terminates quickly once drained
        assertDoesNotThrow(() -> delegate.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(service.isTerminated());
    }

    @Test
    void awaitTermination_delegates() throws Exception {
        service.shutdown();
        assertTrue(service.awaitTermination(2, TimeUnit.SECONDS));
    }
}
