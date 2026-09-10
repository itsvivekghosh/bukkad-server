package com.bhukkad.order;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaInstanceRepository;
import com.bhukkad.common.saga.SagaStepRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Order-service wiring for the platform components plus the module's own
 * async/scheduled execution needs (async order-create facade per migration
 * card §2.3 and the subscription/scheduled-order tickers).
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({ScheduledOrderProperties.class, SubscriptionProperties.class,
        OrderSagaProperties.class})
public class PlatformConfig {

    @Bean
    public OutboxClient outboxClient(OutboxEventRepository outboxEventRepository) {
        return new OutboxClient(outboxEventRepository);
    }

    @Bean
    public SagaCoordinator sagaCoordinator(SagaInstanceRepository sagaInstanceRepository,
                                           SagaStepRepository sagaStepRepository) {
        return new SagaCoordinator(sagaInstanceRepository, sagaStepRepository);
    }

    /** Dedicated pool for the 202+poll async order-create path. */
    @Bean(name = "orderTaskExecutor")
    public ThreadPoolTaskExecutor orderTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("order-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** Background reconciliation/reporting work that must not starve order writes. */
    @Bean(name = "lowPriorityTaskExecutor")
    public ThreadPoolTaskExecutor lowPriorityTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("low-priority-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** Hosts the @Scheduled tickers (ScheduledOrderProcessor, SubscriptionScheduler). */
    @Bean(name = "scheduledTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler scheduledTaskExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
