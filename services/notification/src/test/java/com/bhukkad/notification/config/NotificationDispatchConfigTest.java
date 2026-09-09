package com.bhukkad.notification.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/** P-07 sizing contract: core 4 / max 16 / queue 2000 / AbortPolicy / notify- prefix. */
class NotificationDispatchConfigTest {

    @Test
    void dispatchExecutor_hasAuditRequiredBoundsAndLoudRejection() {
        ThreadPoolTaskExecutor executor =
                new NotificationDispatchConfig().notificationDispatchExecutor(new SimpleMeterRegistry());
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            assertThat(pool.getCorePoolSize()).isEqualTo(4);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(16);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(2000);
            assertThat(pool.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            // Never CallerRuns: overflow must be reject-loud so it reaches the DLT.
            assertThat(pool.getRejectedExecutionHandler())
                    .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void threadNamePrefix_isNotify() {
        ThreadPoolTaskExecutor executor =
                new NotificationDispatchConfig().notificationDispatchExecutor(new SimpleMeterRegistry());
        try {
            assertThat(executor.getThreadNamePrefix()).isEqualTo("notify-");
        } finally {
            executor.shutdown();
        }
    }
}
