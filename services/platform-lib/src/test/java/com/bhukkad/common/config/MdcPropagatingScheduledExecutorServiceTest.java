package com.bhukkad.common.config;

import com.bhukkad.common.logging.LoggingConstants;
import com.bhukkad.common.logging.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MDC-propagating scheduler decorator must wrap every entry point of
 * {@link java.util.concurrent.ScheduledExecutorService}: Runnable paths get a
 * synthetic {@code sched-} job trace id, Callable paths snapshot the caller's
 * MDC so scheduled business work keeps correlating with the request.
 */
class MdcPropagatingScheduledExecutorServiceTest {

    private final ScheduledThreadPoolExecutor raw = new ScheduledThreadPoolExecutor(2);
    private final MdcPropagatingScheduledExecutorService executor =
            new MdcPropagatingScheduledExecutorService(raw);

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        MDC.clear();
    }

    @Test
    void execute_and_runnableSubmits_getSyntheticTraceIds() throws Exception {
        AtomicReference<String> viaExecute = new AtomicReference<>();
        AtomicReference<String> viaSubmit = new AtomicReference<>();
        AtomicReference<String> viaSubmitResult = new AtomicReference<>();

        executor.execute((Runnable) () -> viaExecute.set(MDC.get(LoggingConstants.TRACE_ID)));
        executor.submit((Runnable) () -> viaSubmit.set(MDC.get(LoggingConstants.TRACE_ID)))
                .get(5, TimeUnit.SECONDS);
        assertThat(executor.submit(
                (Runnable) () -> viaSubmitResult.set(MDC.get(LoggingConstants.TRACE_ID)), "payload")
                .get(5, TimeUnit.SECONDS)).isEqualTo("payload");

        assertThat(viaExecute.get()).startsWith("sched-");
        assertThat(viaSubmit.get()).startsWith("sched-");
        assertThat(viaSubmitResult.get()).startsWith("sched-");
    }

    @Test
    void callableSubmits_snapshotTheCallerMdc() throws Exception {
        MDC.put(LoggingConstants.TRACE_ID, "parent-trace");

        assertThat(executor.submit(() -> MDC.get(LoggingConstants.TRACE_ID))
                .get(5, TimeUnit.SECONDS)).isEqualTo("parent-trace");
        assertThat(executor.schedule(() -> {
            assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("parent-trace");
            return MDC.get(LoggingConstants.TRACE_ID);
        }, 10, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)).isEqualTo("parent-trace");

        // snapshot taken at SUBMISSION: mutating parent MDC afterwards does not leak
        AtomicReference<String> late = new AtomicReference<>();
        Callable<String> task = () -> {
            late.set(MDC.get(LoggingConstants.TRACE_ID));
            return "ok";
        };
        executor.submit(task).get(5, TimeUnit.SECONDS);
        MDC.put(LoggingConstants.TRACE_ID, "changed-after");
        executor.submit(task).get(5, TimeUnit.SECONDS);
        assertThat(late.get()).isEqualTo("changed-after"); // second capture is the new value
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("changed-after"); // parent intact
    }

    @Test
    void scheduleRunnable_getsSyntheticTraceId() throws Exception {
        MDC.put(LoggingConstants.TRACE_ID, "parent-trace");
        AtomicReference<String> seenInTask = new AtomicReference<>();
        ScheduledFuture<?> oneShot = executor.schedule((Runnable) () ->
                seenInTask.set(MDC.get(LoggingConstants.TRACE_ID)), 10, TimeUnit.MILLISECONDS);
        oneShot.get(5, TimeUnit.SECONDS);
        assertThat(seenInTask.get()).startsWith("sched-");
    }

    @Test
    void periodicSchedules_areWrappedAndCancellable() throws Exception {
        AtomicReference<String> periodicTrace = new AtomicReference<>();
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(2);
        ScheduledFuture<?> rate = executor.scheduleAtFixedRate(() -> {
            periodicTrace.compareAndSet(null, MDC.get(LoggingConstants.TRACE_ID));
            ran.countDown();
        }, 0, 10, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> delay = executor.scheduleWithFixedDelay(ran::countDown, 0, 10, TimeUnit.MILLISECONDS);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        rate.cancel(true);
        delay.cancel(true);
        assertThat(periodicTrace.get()).startsWith("sched-");
    }

    @Test
    void invokeAll_and_invokeAny_wrapEveryCallable() throws Exception {
        MDC.put(LoggingConstants.TRACE_ID, "caller");
        List<Callable<String>> tasks = List.of(
                () -> MDC.get(LoggingConstants.TRACE_ID),
                () -> MDC.get(LoggingConstants.TRACE_ID));

        var all = executor.invokeAll(tasks);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get()).isEqualTo("caller");
        assertThat(all.get(1).get(2, TimeUnit.SECONDS)).isEqualTo("caller");
        assertThat(executor.invokeAny(tasks)).isEqualTo("caller");
        assertThat(executor.invokeAny(tasks, 2, TimeUnit.SECONDS)).isEqualTo("caller");
    }

    @Test
    void lifecycle_delegatesAndExposesUnderlyingPool() throws Exception {
        assertThat(executor.unwrap()).isSameAs(raw);
        assertThat(executor.isShutdown()).isFalse();
        assertThat(executor.awaitTermination(10, TimeUnit.MILLISECONDS)).isFalse();
        executor.shutdown();
        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.isTerminated()).isTrue();
        assertThat(executor.shutdownNow()).isEmpty();
    }

    @Test
    void named_job_wrapper_variant_is_usable_through_the_pool() {
        Runnable wrapped = TraceContext.wrapWithJobMdc("coverage", () ->
                assertThat(MDC.get(LoggingConstants.TRACE_ID)).startsWith("sched-coverage-"));
        wrapped.run();
    }
}
