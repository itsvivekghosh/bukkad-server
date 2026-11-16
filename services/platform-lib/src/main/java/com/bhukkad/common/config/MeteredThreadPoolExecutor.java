package com.bhukkad.common.config;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ThreadPoolExecutor} that counts rejected tasks so platform-lib can
 * expose a {@code pool_rejected} gauge per named scheduler.
 *
 * <p>Each named scheduler (jdbc, redis, external-api) is bounded: core threads,
 * a bounded queue, then rejection. Rejection uses AbortPolicy semantics (throw
 * {@link RejectedExecutionException}) but increments a monotonic counter first,
 * so saturation is observable through the {@code pool_rejected} gauge rather
 * than silent.
 */
public class MeteredThreadPoolExecutor extends ThreadPoolExecutor {

    private final AtomicLong rejected = new AtomicLong();

    public MeteredThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
                                     TimeUnit unit, BlockingQueue<Runnable> workQueue,
                                     ThreadFactory threadFactory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory,
                rejectionHandler());
    }

    /** Monotonic count of tasks rejected because pool + queue were saturated. */
    public long getRejectedCount() {
        return rejected.get();
    }

    private static RejectedExecutionHandler rejectionHandler() {
        return (r, executor) -> {
            // Count the rejection before aborting so the saturation signal is
            // visible in Metrics even though the task itself is not run.
            MeteredThreadPoolExecutor metered = unwrap(executor);
            if (metered != null) {
                metered.rejected.incrementAndGet();
            }
            throw new RejectedExecutionException(
                    "Task " + r + " rejected from saturated pool " + executor);
        };
    }

    @SuppressWarnings("unchecked")
    private static MeteredThreadPoolExecutor unwrap(ThreadPoolExecutor executor) {
        return executor instanceof MeteredThreadPoolExecutor
                ? (MeteredThreadPoolExecutor) executor
                : null;
    }
}
