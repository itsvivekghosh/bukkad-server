package com.bhukkad.config;

import com.bhukkad.logging.MdcTaskDecorator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Value("${app.async.order.core-pool-size:4}")
    private int orderCorePoolSize;

    @Value("${app.async.order.max-pool-size:16}")
    private int orderMaxPoolSize;

    @Value("${app.async.order.queue-capacity:200}")
    private int orderQueueCapacity;

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
}
