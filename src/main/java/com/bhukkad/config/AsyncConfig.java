package com.bhukkad.config;

import com.bhukkad.logging.MdcTaskDecorator;
import com.bhukkad.logging.TraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Wires the application's async and scheduled execution. Two important details
 * to preserve when reading this file:
 *
 *  1. {@link #orderTaskExecutor()} uses {@link MdcTaskDecorator} so any
 *     {@code @Async("orderTaskExecutor")} work inherits the request's MDC
 *     (traceId, requestId, user). Without that, async order work would log
 *     with {@code trace:-} and become uncorrelatable from the originating HTTP
 *     request.
 *
 *  2. {@link #configureTasks(ScheduledTaskRegistrar)} installs a
 *     {@link ThreadPoolTaskScheduler} whose underlying
 *     {@link ScheduledExecutorService} wraps every Runnable in an MDC scope
 *     via {@link TraceContext#wrapWithJobMdc(Runnable)}. {@code @Scheduled}
 *     jobs are not initiated by an HTTP request, so without this wrapper the
 *     Hibernate SQL and DEBUG traces they emit would all carry
 *     {@code trace:-}.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements SchedulingConfigurer {

    @Value("${app.async.order.core-pool-size:4}")
    private int orderCorePoolSize;

    @Value("${app.async.order.max-pool-size:16}")
    private int orderMaxPoolSize;

    @Value("${app.async.order.queue-capacity:200}")
    private int orderQueueCapacity;

    @Value("${app.async.low.core-pool-size:2}")
    private int lowCorePoolSize;

    @Value("${app.async.low.max-pool-size:4}")
    private int lowMaxPoolSize;

    @Value("${app.async.low.queue-capacity:100}")
    private int lowQueueCapacity;

    @Bean(name = "orderTaskExecutor")
    public Executor orderTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(orderCorePoolSize);
        executor.setMaxPoolSize(orderMaxPoolSize);
        executor.setQueueCapacity(orderQueueCapacity);
        executor.setThreadNamePrefix("order-async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Low-priority tier for non-urgent fan-out work (notifications, analytics
     * materialization, cache warming) so it never starves the order-placement
     * path. Uses the {@code app.async.low.*} configuration block.
     */
    @Bean(name = "lowPriorityTaskExecutor")
    public Executor lowPriorityTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(lowCorePoolSize);
        executor.setMaxPoolSize(lowMaxPoolSize);
        executor.setQueueCapacity(lowQueueCapacity);
        executor.setThreadNamePrefix("low-priority-async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated scheduler pool so cron jobs do not starve each other and do
     * not share threads with the embedded Tomcat pool. Size is small on
     * purpose: scheduled work is bounded and any real concurrency belongs in
     * {@link #orderTaskExecutor()}, not here.
     *
     * <p>Subclassing is necessary because {@link ThreadPoolTaskScheduler}
     * exposes no setter for the underlying {@link ScheduledExecutorService};
     * the only extension point is {@code createExecutor()}, which we override
     * to wrap the raw executor in
     * {@link MdcPropagatingScheduledExecutorService}.
     */
    @Bean(name = "scheduledTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler scheduledTaskExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler() {
            @Override
            protected ScheduledExecutorService createExecutor(int poolSize, java.util.concurrent.ThreadFactory threadFactory, java.util.concurrent.RejectedExecutionHandler rejectedExecutionHandler) {
                ScheduledExecutorService raw = super.createExecutor(poolSize, threadFactory, rejectedExecutionHandler);
                return new MdcPropagatingScheduledExecutorService(raw);
            }
        };
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Dedicated bounded pool for live-update fan-out (SSE/STOMP dispatch).
     *
     * <p>Live updates arrive on the Redis pub/sub listener thread. Fan-out to
     * SSE emitters performs blocking socket writes, and a slow client can stall
     * its worker. Running that fan-out on this dedicated pool (instead of the
     * Redis listener thread or the shared scheduler) keeps one slow SSE
     * subscriber from blocking Redis message delivery, the scheduled jobs, or
     * the order path. Queue is bounded: when it is full, an update is dropped
     * for that delivery cycle and the affected client recovers via the replay
     * store on its next reconnect.
     */
    @Bean(name = "sseDispatchExecutor")
    public Executor sseDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("sse-dispatch-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Hooks Spring's @Scheduled machinery to use {@link #scheduledTaskExecutor()}.
     * The MDC wrap is applied at the executor level (see above), so this method
     * is a thin pass-through; we only need to wire the scheduler into the
     * registrar.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(scheduledTaskExecutor());
    }
}
