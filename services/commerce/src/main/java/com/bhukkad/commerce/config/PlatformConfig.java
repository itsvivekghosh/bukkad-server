package com.bhukkad.commerce.config;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaInstanceRepository;
import com.bhukkad.common.saga.SagaStepRepository;
import com.bhukkad.commerce.delivery.config.RiderEarningsProperties;
import com.bhukkad.commerce.delivery.config.RiderLocationRetentionProperties;
import com.bhukkad.commerce.order.config.OrderSagaProperties;
import com.bhukkad.commerce.order.config.ScheduledOrderProperties;
import com.bhukkad.commerce.order.config.SubscriptionProperties;
import com.bhukkad.commerce.payment.PaymentProperties;
import com.bhukkad.commerce.payment.config.SettlementAutomationProperties;
import com.bhukkad.commerce.payment.domain.service.PaymentService;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Single platform wiring for the merged commerce service (order + payment + delivery).
 *
 * <p>Before the consolidation each of the three source services declared its own
 * {@code PlatformConfig}. Merged into one application they collided on bean
 * <em>method</em> names, not just class names: {@code outboxClient},
 * {@code orderTaskExecutor}, {@code lowPriorityTaskExecutor} and
 * {@code scheduledTaskExecutor} were each declared two or three times, which
 * Spring rejects outright (BeanDefinitionOverrideException by default).
 *
 * <p>This class is the de-duplicated union — every bean is declared exactly once:
 * <ul>
 *   <li>{@code outboxClient} / {@code sagaCoordinator} — platform outbox + saga wiring</li>
 *   <li>{@code paymentCurrencyGateway} — payment charge-path currency source</li>
 *   <li>{@code lockProvider} — ShedLock, used by the rider-location retention purge</li>
 *   <li>{@code sseDispatchExecutor} — fixed 8/500 with AbortPolicy (audit V-06:
 *       a saturated SSE consumer is evicted, never run on the caller thread)</li>
 * </ul>
 *
 * <p>Note {@code scheduledTaskExecutor} uses poolSize 8 (the delivery value).
 * The order variant used 4; 8 is a safe superset for the union of both tickers.
 */
@Configuration
@EnableAsync
@EnableScheduling
// ShedLock for the rider-location retention purge. defaultLockAtMostFor bounds
// a forgotten release.
@EnableSchedulerLock(defaultLockAtMostFor = "PT25M")
@EnableConfigurationProperties({
        ScheduledOrderProperties.class,
        SubscriptionProperties.class,
        OrderSagaProperties.class,
        SettlementAutomationProperties.class,
        PaymentProperties.class,
        RiderEarningsProperties.class,
        RiderLocationRetentionProperties.class
})
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

    /** Currency source for the charge path (keeps PaymentService decoupled from the full properties). */
    @Bean
    public PaymentService.PaymentPropertiesGateway paymentCurrencyGateway(PaymentProperties properties) {
        return properties.getRazorpay()::getCurrency;
    }

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .build());
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

    /**
     * SSE send pool: fixed size, explicit AbortPolicy. CallerRuns was removed
     * (audit V-06 finish): a blocked socket used to fall back to running the
     * write on the caller (broadcast/heartbeat) thread, turning one slow
     * consumer into head-of-line blocking for the whole dispatch loop. On
     * saturation the caller now EVICTS the emitter (OrderSseStreamService
     * drops + counts it) instead of waiting for it.
     */
    @Bean(name = "sseDispatchExecutor")
    public ThreadPoolTaskExecutor sseDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("sse-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** Hosts the @Scheduled tickers (ScheduledOrderProcessor, SubscriptionScheduler). */
    @Bean(name = "scheduledTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler scheduledTaskExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
