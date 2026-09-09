package com.bhukkad.realtime.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(LiveProperties.class)
public class RealtimeConfig {

    /**
     * Dedicated SSE send pool (audit V-06 finish): FIXED size with explicit
     * AbortPolicy — never CallerRuns, because a blocked socket being served
     * inline would let one slow consumer head-of-line-block every broadcast
     * and heartbeat. On saturation callers evict the emitter and count it
     * ({@code sse_capacity_rejected{reason=dispatch}}).
     */
    @Bean("sseDispatchExecutor")
    public Executor sseDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("sse-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
