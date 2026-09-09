package com.bhukkad.common.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Exposes executor pools to Micrometer/Prometheus for HPA custom metrics and Grafana.
 * Without this, queue saturation and thread starvation are invisible until 503s.
 *
 * <p>Every pool is optional: platform-lib is scanned by all services, but only
 * order/delivery define the command executors (SSE fan-out is delivery's;
 * async order creation is order's). Missing pools are silently skipped so the
 * shared config never blocks a service context.</p>
 */
@Slf4j
@Configuration
public class ExecutorMetricsConfig {

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<ThreadPoolTaskExecutor> orderTaskExecutor;
    private final ObjectProvider<ThreadPoolTaskExecutor> lowPriorityTaskExecutor;
    private final ObjectProvider<ThreadPoolTaskExecutor> sseDispatchExecutor;
    private final ObjectProvider<ThreadPoolTaskScheduler> scheduledTaskExecutor;

    public ExecutorMetricsConfig(MeterRegistry meterRegistry,
                                 @Qualifier("orderTaskExecutor") ObjectProvider<ThreadPoolTaskExecutor> orderTaskExecutor,
                                 @Qualifier("lowPriorityTaskExecutor") ObjectProvider<ThreadPoolTaskExecutor> lowPriorityTaskExecutor,
                                 @Qualifier("sseDispatchExecutor") ObjectProvider<ThreadPoolTaskExecutor> sseDispatchExecutor,
                                 @Qualifier("scheduledTaskExecutor") ObjectProvider<ThreadPoolTaskScheduler> scheduledTaskExecutor) {
        this.meterRegistry = meterRegistry;
        this.orderTaskExecutor = orderTaskExecutor;
        this.lowPriorityTaskExecutor = lowPriorityTaskExecutor;
        this.sseDispatchExecutor = sseDispatchExecutor;
        this.scheduledTaskExecutor = scheduledTaskExecutor;
    }

    @PostConstruct
    void bindMetrics() {
        try {
            bindExecutor("orderTaskExecutor", orderTaskExecutor.getIfAvailable());
            bindExecutor("lowPriorityTaskExecutor", lowPriorityTaskExecutor.getIfAvailable());
            bindExecutor("sseDispatchExecutor", sseDispatchExecutor.getIfAvailable());
            bindScheduler(scheduledTaskExecutor.getIfAvailable());
            log.debug("EXECUTOR_METRICS_BOUND | missing pools skipped");
        } catch (Exception e) {
            log.warn("Failed to bind executor metrics: {}", e.getMessage());
        }
    }

    private void bindExecutor(String name, ThreadPoolTaskExecutor executor) {
        if (executor == null) {
            return;
        }
        // ThreadPoolTaskExecutor is not an ExecutorService, wrap via getThreadPoolExecutor()
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), name);
        Gauge.builder("executor.queue.remaining", executor,
                        e -> e.getQueueCapacity() - e.getThreadPoolExecutor().getQueue().size())
                .description("Remaining queue capacity for " + name)
                .tag("executor", name)
                .register(meterRegistry);
        Gauge.builder("executor.active.count", executor.getThreadPoolExecutor(), ThreadPoolExecutor::getActiveCount)
                .tag("executor", name)
                .register(meterRegistry);
    }

    private void bindScheduler(ThreadPoolTaskScheduler scheduler) {
        if (scheduler == null) {
            return;
        }
        // Scheduler is a ThreadPoolTaskScheduler wrapping a ScheduledExecutorService
        // that is itself wrapped in the MDC propagator. ExecutorServiceMetrics cannot
        // instrument the decorator (unsupported class), so bind the concrete pool
        // underneath it — that is where pool/queue state actually lives.
        java.util.concurrent.ScheduledExecutorService scheduled = scheduler.getScheduledExecutor();
        if (scheduled instanceof MdcPropagatingScheduledExecutorService mdcWrapped) {
            scheduled = mdcWrapped.unwrap();
        }
        ExecutorServiceMetrics.monitor(meterRegistry, scheduled, "scheduledTaskExecutor");
    }
}
