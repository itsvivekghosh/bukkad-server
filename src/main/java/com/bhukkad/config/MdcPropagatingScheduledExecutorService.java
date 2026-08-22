package com.bhukkad.config;

import com.bhukkad.logging.TraceContext;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link ScheduledExecutorService} decorator that wraps every submitted task
 * with a fresh MDC scope via {@link TraceContext#wrapWithJobMdc(Runnable)}.
 *
 * <p>Spring's {@code @Scheduled} machinery submits {@code ReschedulingRunnable}
 * instances directly to the executor; subclassing the scheduler or overriding
 * its {@code execute} method does not reliably catch them, so the wrapping has
 * to happen here, at the lowest level. Every task that ends up running on the
 * scheduled-task pool — one-shot, fixed-rate, fixed-delay, or cron — goes
 * through one of the {@code execute} / {@code submit} / {@code schedule}
 * methods below, all of which now pass the task through the wrapper.
 */
final class MdcPropagatingScheduledExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;

    MdcPropagatingScheduledExecutorService(ScheduledExecutorService delegate) {
        this.delegate = delegate;
    }

    // ---------- ScheduledExecutorService ----------

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate.schedule(TraceContext.wrapWithJobMdc(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return delegate.schedule(wrapCallable(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return delegate.scheduleAtFixedRate(TraceContext.wrapWithJobMdc(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return delegate.scheduleWithFixedDelay(TraceContext.wrapWithJobMdc(command), initialDelay, delay, unit);
    }

    // ---------- ExecutorService ----------

    @Override
    public void execute(Runnable command) {
        delegate.execute(TraceContext.wrapWithJobMdc(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(TraceContext.wrapWithJobMdc(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(TraceContext.wrapWithJobMdc(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrapCallable(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapCallables(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapCallables(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapCallables(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapCallables(tasks), timeout, unit);
    }

    // ---------- Executor ----------

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public java.util.List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    // ---------- helpers ----------

    private static <T> Callable<T> wrapCallable(Callable<T> callable) {
        return () -> {
            // Capture parent's MDC at submission time and restore for the duration
            // of the call. Scheduled callables run on the same pool, so without
            // this they'd inherit the empty scheduler-thread MDC.
            java.util.Map<String, String> previous = TraceContext.copy();
            try {
                return callable.call();
            } finally {
                TraceContext.restore(previous);
            }
        };
    }

    private static <T> java.util.Collection<? extends Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks) {
        java.util.List<Callable<T>> wrapped = new java.util.ArrayList<>(tasks.size());
        for (Callable<T> t : tasks) {
            wrapped.add(wrapCallable(t));
        }
        return wrapped;
    }

    // Unused but kept here so future additions don't drift from the interface.

    @SuppressWarnings("unused")
    private static void unusedDelayedReference(Delayed d) { /* interface completeness */ }
}
