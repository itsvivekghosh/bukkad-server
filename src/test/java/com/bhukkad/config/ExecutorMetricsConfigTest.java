package com.bhukkad.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Batch D executor metrics binding (HPA custom metrics source).
 * Verifies that every executor pool is bound to Micrometer and that the
 * queue-remaining / active-count gauges are registered with sane values.
 */
class ExecutorMetricsConfigTest {

    private SimpleMeterRegistry registry;
    private ThreadPoolTaskExecutor orderExecutor;
    private ThreadPoolTaskExecutor lowExecutor;
    private ThreadPoolTaskExecutor sseExecutor;
    private ThreadPoolTaskScheduler scheduler;
    private ExecutorMetricsConfig config;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        orderExecutor = executor("order-test-");
        lowExecutor = executor("low-test-");
        sseExecutor = executor("sse-test-");
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sched-test-");
        scheduler.initialize();
        config = new ExecutorMetricsConfig(registry, orderExecutor, lowExecutor, sseExecutor, scheduler);
    }

    private ThreadPoolTaskExecutor executor(String prefix) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(2);
        e.setQueueCapacity(10);
        e.setThreadNamePrefix(prefix);
        e.initialize();
        return e;
    }

    @AfterEach
    void tearDown() {
        orderExecutor.shutdown();
        lowExecutor.shutdown();
        sseExecutor.shutdown();
        scheduler.shutdown();
    }

    @Test
    void bindMetrics_registersQueueRemainingGaugesForEachExecutor() {
        config.bindMetrics();

        Gauge orderRemaining = registry.find("executor.queue.remaining")
                .tag("executor", "orderTaskExecutor").gauge();
        Gauge lowRemaining = registry.find("executor.queue.remaining")
                .tag("executor", "lowPriorityTaskExecutor").gauge();
        Gauge sseRemaining = registry.find("executor.queue.remaining")
                .tag("executor", "sseDispatchExecutor").gauge();

        assertThat(orderRemaining).isNotNull();
        assertThat(lowRemaining).isNotNull();
        assertThat(sseRemaining).isNotNull();
        // Queue capacity is 10 with nothing queued
        assertThat(orderRemaining.value()).isEqualTo(10.0);
        assertThat(lowRemaining.value()).isEqualTo(10.0);
        assertThat(sseRemaining.value()).isEqualTo(10.0);
    }

    @Test
    void bindMetrics_registersActiveCountGauge() {
        config.bindMetrics();

        Gauge active = registry.find("executor.active.count")
                .tag("executor", "orderTaskExecutor").gauge();
        assertThat(active).isNotNull();
        assertThat(active.value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void bindMetrics_bindsExecutorServiceMetricsForAllPools() {
        config.bindMetrics();

        // ExecutorServiceMetrics.monitor registers executor.queued (among others)
        // tagged with name=<metricName> for each of the four pools.
        assertThat(registry.find("executor.queued").tag("name", "orderTaskExecutor").gauge()).isNotNull();
        assertThat(registry.find("executor.queued").tag("name", "lowPriorityTaskExecutor").gauge()).isNotNull();
        assertThat(registry.find("executor.queued").tag("name", "sseDispatchExecutor").gauge()).isNotNull();
        // Scheduler is bound via getScheduledExecutor()
        assertThat(registry.find("executor.queued").tag("name", "scheduledTaskExecutor").gauge()).isNotNull();
    }

    @Test
    void bindMetrics_reflectsQueuedTasksInRemainingGauge() throws Exception {
        config.bindMetrics();
        // Queue a long-running task so one worker is busy and the queue holds one pending task
        orderExecutor.getThreadPoolExecutor().submit(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        orderExecutor.getThreadPoolExecutor().submit(() -> { /* queued */ });
        Thread.sleep(100); // allow first task to start, second to enqueue

        Gauge remaining = registry.find("executor.queue.remaining")
                .tag("executor", "orderTaskExecutor").gauge();
        assertThat(remaining.value()).isEqualTo(9.0);
    }

    @Test
    void bindMetrics_swallowsBindingFailure() {
        // Executor whose getThreadPoolExecutor() blows up → monitor() throws → caught
        ThreadPoolTaskExecutor broken = mock(ThreadPoolTaskExecutor.class);
        when(broken.getThreadPoolExecutor()).thenThrow(new IllegalStateException("boom"));

        ExecutorMetricsConfig brokenConfig = new ExecutorMetricsConfig(
                registry, broken, lowExecutor, sseExecutor, scheduler);

        assertDoesNotThrow(brokenConfig::bindMetrics);
        // Failure happened before any gauge registration — registry stays empty of our gauges
        assertThat(registry.find("executor.queue.remaining").gauge()).isNull();
    }
}
