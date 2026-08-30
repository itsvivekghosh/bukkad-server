package com.bhukkad.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Exposes executor pools to Micrometer/Prometheus for HPA custom metrics and Grafana.
 * Without this, queue saturation and thread starvation are invisible until 503s.
 */
@Slf4j
@Configuration
public class ExecutorMetricsConfig {

    private final MeterRegistry meterRegistry;
    private final ThreadPoolTaskExecutor orderTaskExecutor;
    private final ThreadPoolTaskExecutor lowPriorityTaskExecutor;
    private final ThreadPoolTaskExecutor sseDispatchExecutor;
    private final ThreadPoolTaskScheduler scheduledTaskExecutor;

    public ExecutorMetricsConfig(MeterRegistry meterRegistry,
                                 @Qualifier("orderTaskExecutor") ThreadPoolTaskExecutor orderTaskExecutor,
                                 @Qualifier("lowPriorityTaskExecutor") ThreadPoolTaskExecutor lowPriorityTaskExecutor,
                                 @Qualifier("sseDispatchExecutor") ThreadPoolTaskExecutor sseDispatchExecutor,
                                 @Qualifier("scheduledTaskExecutor") ThreadPoolTaskScheduler scheduledTaskExecutor) {
        this.meterRegistry = meterRegistry;
        this.orderTaskExecutor = orderTaskExecutor;
        this.lowPriorityTaskExecutor = lowPriorityTaskExecutor;
        this.sseDispatchExecutor = sseDispatchExecutor;
        this.scheduledTaskExecutor = scheduledTaskExecutor;
    }

    @PostConstruct
    void bindMetrics() {
        try {
            // ThreadPoolTaskExecutor is not an ExecutorService, wrap via getThreadPoolExecutor()
            ExecutorServiceMetrics.monitor(meterRegistry, orderTaskExecutor.getThreadPoolExecutor(), "orderTaskExecutor");
            ExecutorServiceMetrics.monitor(meterRegistry, lowPriorityTaskExecutor.getThreadPoolExecutor(), "lowPriorityTaskExecutor");
            ExecutorServiceMetrics.monitor(meterRegistry, sseDispatchExecutor.getThreadPoolExecutor(), "sseDispatchExecutor");
            // Scheduler is a ThreadPoolTaskScheduler wrapping a ScheduledExecutorService
            // that is itself wrapped in the MDC propagator. ExecutorServiceMetrics cannot
            // instrument the decorator (unsupported class), so bind the concrete pool
            // underneath it — that is where pool/queue state actually lives.
            java.util.concurrent.ScheduledExecutorService scheduled =
                    scheduledTaskExecutor.getScheduledExecutor();
            if (scheduled instanceof MdcPropagatingScheduledExecutorService mdcWrapped) {
                scheduled = mdcWrapped.unwrap();
            }
            ExecutorServiceMetrics.monitor(meterRegistry, scheduled, "scheduledTaskExecutor");

            // Queue remaining capacity gauges (alert when <10)
            Gauge.builder("executor.queue.remaining", orderTaskExecutor, e -> e.getQueueCapacity() - e.getThreadPoolExecutor().getQueue().size())
                    .description("Remaining queue capacity for orderTaskExecutor")
                    .tag("executor", "orderTaskExecutor")
                    .register(meterRegistry);
            Gauge.builder("executor.queue.remaining", lowPriorityTaskExecutor, e -> e.getQueueCapacity() - e.getThreadPoolExecutor().getQueue().size())
                    .description("Remaining queue capacity for lowPriorityTaskExecutor")
                    .tag("executor", "lowPriorityTaskExecutor")
                    .register(meterRegistry);
            Gauge.builder("executor.queue.remaining", sseDispatchExecutor, e -> e.getQueueCapacity() - e.getThreadPoolExecutor().getQueue().size())
                    .description("Remaining queue capacity for sseDispatchExecutor")
                    .tag("executor", "sseDispatchExecutor")
                    .register(meterRegistry);

            // Active count gauges
            Gauge.builder("executor.active.count", orderTaskExecutor.getThreadPoolExecutor(), ThreadPoolExecutor::getActiveCount)
                    .tag("executor", "orderTaskExecutor")
                    .register(meterRegistry);

            log.info("EXECUTOR_METRICS_BOUND | order/low/sse/scheduled bound to Prometheus");
        } catch (Exception e) {
            log.warn("Failed to bind executor metrics: {}", e.getMessage());
        }
    }
}
