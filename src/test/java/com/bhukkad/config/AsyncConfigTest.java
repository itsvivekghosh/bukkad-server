package com.bhukkad.config;

import com.bhukkad.logging.MdcTaskDecorator;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    @Test
    void orderTaskExecutor_usesConfiguredPoolSizes() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "orderCorePoolSize", 2);
        ReflectionTestUtils.setField(config, "orderMaxPoolSize", 4);
        ReflectionTestUtils.setField(config, "orderQueueCapacity", 10);

        Executor executor = config.orderTaskExecutor();

        assertNotNull(executor);
    }

    @Test
    void orderTaskExecutor_appliesConfiguredSizesAndNaming() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "orderCorePoolSize", 2);
        ReflectionTestUtils.setField(config, "orderMaxPoolSize", 4);
        ReflectionTestUtils.setField(config, "orderQueueCapacity", 10);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.orderTaskExecutor();
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(4, executor.getMaxPoolSize());
            assertEquals(10, executor.getQueueCapacity());
            assertEquals("order-async-", executor.getThreadNamePrefix());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void orderTaskExecutor_installsMdcTaskDecorator() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "orderCorePoolSize", 1);
        ReflectionTestUtils.setField(config, "orderMaxPoolSize", 1);
        ReflectionTestUtils.setField(config, "orderQueueCapacity", 1);

        Executor executor = config.orderTaskExecutor();
        try {
            Object decorator = ReflectionTestUtils.getField(executor, "taskDecorator");
            assertNotNull(decorator);
            assertTrue(decorator instanceof MdcTaskDecorator);
        } finally {
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }

    @Test
    void scheduledTaskExecutor_wrapsUnderlyingExecutorInMdcPropagation() {
        AsyncConfig config = new AsyncConfig();

        ThreadPoolTaskScheduler scheduler = config.scheduledTaskExecutor();
        try {
            assertEquals(8, (Integer) ReflectionTestUtils.getField(scheduler, "poolSize"));
            assertEquals("sched-", (String) ReflectionTestUtils.getField(scheduler, "threadNamePrefix"));
            assertEquals(Boolean.TRUE,
                    (Boolean) ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown"));
            assertEquals(30_000L,
                    ((Long) ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis")).longValue());
            assertTrue(scheduler.getScheduledExecutor() instanceof MdcPropagatingScheduledExecutorService);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void configureTasks_wiresMdcWrappedSchedulerIntoRegistrar() {
        AsyncConfig config = new AsyncConfig();
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        config.configureTasks(registrar);

        TaskScheduler wired = registrar.getScheduler();
        assertNotNull(wired);
        assertTrue(wired instanceof ThreadPoolTaskScheduler);
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) wired;
        try {
            assertEquals(8, (Integer) ReflectionTestUtils.getField(scheduler, "poolSize"));
            assertTrue(scheduler.getScheduledExecutor() instanceof MdcPropagatingScheduledExecutorService);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void lowPriorityTaskExecutor_usesConfiguredSizesAndNaming() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "lowCorePoolSize", 1);
        ReflectionTestUtils.setField(config, "lowMaxPoolSize", 2);
        ReflectionTestUtils.setField(config, "lowQueueCapacity", 5);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.lowPriorityTaskExecutor();
        try {
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(2, executor.getMaxPoolSize());
            assertEquals(5, executor.getQueueCapacity());
            assertEquals("low-priority-async-", executor.getThreadNamePrefix());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void lowPriorityTaskExecutor_installsMdcTaskDecorator() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "lowCorePoolSize", 1);
        ReflectionTestUtils.setField(config, "lowMaxPoolSize", 1);
        ReflectionTestUtils.setField(config, "lowQueueCapacity", 1);

        Executor executor = config.lowPriorityTaskExecutor();
        try {
            Object decorator = ReflectionTestUtils.getField(executor, "taskDecorator");
            assertNotNull(decorator);
            assertTrue(decorator instanceof MdcTaskDecorator);
        } finally {
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }

    @Test
    void sseDispatchExecutor_usesBoundedPoolWithCallerRunsPolicy() {
        AsyncConfig config = new AsyncConfig();

        ThreadPoolTaskExecutor executor = config.sseDispatchExecutor();
        try {
            // Batch D: bounded queue (500) so SSE fan-out saturates visibly
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(8, executor.getMaxPoolSize());
            assertEquals(500, executor.getQueueCapacity());
            assertEquals("sse-dispatch-", executor.getThreadNamePrefix());
            // CallerRunsPolicy: when the queue is full the Redis listener thread runs
            // the dispatch inline instead of silently dropping the update.
            assertTrue(executor.getThreadPoolExecutor().getRejectedExecutionHandler()
                    instanceof java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void sseDispatchExecutor_installsMdcTaskDecorator() {
        AsyncConfig config = new AsyncConfig();

        ThreadPoolTaskExecutor executor = config.sseDispatchExecutor();
        try {
            Object decorator = ReflectionTestUtils.getField(executor, "taskDecorator");
            assertNotNull(decorator);
            assertTrue(decorator instanceof MdcTaskDecorator);
        } finally {
            executor.shutdown();
        }
    }
}
