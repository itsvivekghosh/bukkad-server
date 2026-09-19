package com.bhukkad.common.async;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Shared async task executor configuration.
 *
 * <p>Provides a bounded thread pool for {@code @Async} methods across
 * services. Core pool size matches the number of service threads; the max
 * pool size allows burst handling without unbounded queue growth.
 *
 * <p>For 200k+ TPS workloads, pool sizes are tuned aggressively:
 * core pools scale with available processors, and a bounded queue with
 * CallerRunsPolicy prevents OOM under backpressure.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(
            @Value("${app.async.core-pool-size:32}") int corePoolSize,
            @Value("${app.async.max-pool-size:64}") int maxPoolSize,
            @Value("${app.async.queue-capacity:500}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("bhukkad-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAwaitTerminationSeconds(30);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
