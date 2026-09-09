package com.bhukkad.common.outbox;

import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the outbox relay onto its own two-thread {@code relay-} scheduler.
 *
 * <p>Single gate (PERF-2/B2): the {@code @ConditionalOnExpression} here is the
 * exact same expression used by
 * {@link com.bhukkad.common.kafka.KafkaPlatformConfig} — relay, KafkaTemplate,
 * listener factory and publisher all come up together under
 * {@code app.events.external.enabled=true} AND
 * {@code app.events.external.type=kafka}, and all stay down otherwise. The
 * relay-while-publisher-disabled blackhole (rows flipping to PUBLISHED with
 * nothing sent) is structurally unreachable: bean existence is aligned, and
 * {@link KafkaPlatformEventPublisher#publishForResult} additionally returns
 * {@code false} when disabled. The prod boot preflight
 * ({@link com.bhukkad.common.config.preflight.EventBackbonePreflight}) refuses
 * any prod boot that leaves the backbone disabled.</p>
 *
 * <p>The relay no longer runs on the shared default {@code @Scheduled} pool
 * (B1: 14 jobs contending one scheduler thread while {@code
 * ScheduledOrderProcessor} busy-loops): {@link OutboxRelayBootstrap} registers
 * the poll and stale-recovery loops on a dedicated, {@code relay-} prefixed,
 * two-thread {@link ThreadPoolTaskScheduler} once the context is ready.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@ConditionalOnExpression(
        "'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka'")
public class OutboxPlatformConfig {

    /** Dedicated scheduler for the relay loops — never the shared default scheduler. */
    @Bean(name = "outboxRelayScheduler", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "outboxRelayScheduler")
    public ThreadPoolTaskScheduler outboxRelayScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("relay-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionTemplate outboxRelayTransactionTemplate(PlatformTransactionManager transactionManager) {
        // The relay's claim/state transactions: REQUIRED joins an ambient tx
        // (tests), otherwise runs its own short transaction — exactly the
        // transaction discipline B1 demanded.
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxPollPublisher outboxPollPublisher(OutboxEventRepository repository,
                                                   KafkaPlatformEventPublisher publisher,
                                                   OutboxProperties properties,
                                                   TransactionTemplate outboxRelayTransactionTemplate,
                                                   DeadLetterEventService deadLetterEvents,
                                                   io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new OutboxPollPublisher(repository, publisher, properties,
                outboxRelayTransactionTemplate, deadLetterEvents, meterRegistry);
    }

    /** Starts the relay loops on the dedicated scheduler once the app is ready. */
    @Bean
    @ConditionalOnBean(OutboxPollPublisher.class)
    public OutboxRelayBootstrap outboxRelayBootstrap(OutboxPollPublisher relay,
                                                     OutboxProperties properties,
                                                     ThreadPoolTaskScheduler outboxRelayScheduler) {
        return new OutboxRelayBootstrap(relay, properties, outboxRelayScheduler);
    }
}
