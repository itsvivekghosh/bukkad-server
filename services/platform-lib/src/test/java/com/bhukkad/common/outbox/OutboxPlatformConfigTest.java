package com.bhukkad.common.outbox;

import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * PERF-2/B2: the relay comes up IF AND ONLY IF the Kafka publisher does —
 * relay and publisher share the one {@code ExternalEventsProperties} gate
 * (enabled=true AND type=kafka), closing the split-gate blackhole.
 */
class OutboxPlatformConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
            .withBean(DeadLetterEventService.class, () -> mock(DeadLetterEventService.class))
            .withBean(KafkaPlatformEventPublisher.class, () -> mock(KafkaPlatformEventPublisher.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(OutboxPlatformConfig.class);

    @Test
    void nothingRelays_whenEventsDisabled() {
        contextRunner.run(context -> {
            assertThat(context.getBeansOfType(OutboxPollPublisher.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class))
                    .isEmpty();
        });
    }

    @Test
    void nothingRelays_whenEnabledButLogTransport() {
        contextRunner
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=log")
                .run(context -> {
                    // The exact B2 combo (relay on while publisher off) is now
                    // structurally impossible: one gate, nothing is created.
                    assertThat(context.getBeansOfType(OutboxPollPublisher.class)).isEmpty();
                });
    }

    @Test
    void relayAndBootstrap_whenEnabledAndKafka() {
        contextRunner
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=kafka",
                        "app.events.external.kafka.bootstrap-servers=localhost:9092")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxPollPublisher.class);
                    assertThat(context).hasSingleBean(OutboxRelayBootstrap.class);
                    assertThat(context).hasSingleBean(
                            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
                });
    }

    // ── P-06 wake channel gating (app.outbox.wake.enabled, default FALSE) ────

    @Test
    void wakeDisabledByDefault_noWakePublisherBean() {
        contextRunner
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=kafka",
                        "app.events.external.kafka.bootstrap-servers=localhost:9092")
                .run(context -> {
                    // Disabled-by-default preserves the poll-only behaviour:
                    // no wake bean → OutboxClient registers no synchronization.
                    assertThat(context.getBeansOfType(OutboxWakePublisher.class)).isEmpty();
                    assertThat(context).hasSingleBean(OutboxRelayBootstrap.class);
                });
    }

    @Test
    void wakeEnabled_noRedis_bootstrapDegradesToPollOnly() {
        contextRunner
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=kafka",
                        "app.events.external.kafka.bootstrap-servers=localhost:9092",
                        "app.outbox.wake.enabled=true")
                .run(context -> {
                    // Wake requested but no Redis: the no-op publisher keeps
                    // enqueue behaviour identical; the bootstrap still starts.
                    assertThat(context).hasSingleBean(OutboxWakePublisher.class);
                    OutboxWakePublisher wake = context.getBean(OutboxWakePublisher.class);
                    assertThatCode(() -> wake.publishAfterCommit("OrderCreated")).doesNotThrowAnyException();
                    assertThat(context).hasSingleBean(OutboxRelayBootstrap.class);
                });
    }

    @Test
    void wakeEnabled_withRedis_redisBackedPublisher() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        contextRunner
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=kafka",
                        "app.events.external.kafka.bootstrap-servers=localhost:9092",
                        "app.outbox.wake.enabled=true",
                        "spring.application.name=order")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxWakePublisher.class);
                    context.getBean(OutboxWakePublisher.class).publishAfterCommit("OrderCreated");
                    verify(redisTemplate).convertAndSend("bhukkad:outbox:wake:order", "OrderCreated");
                });
    }

    @Test
    void wakeEnabled_withRedisConnectionFactory_bootstrapSubscribesWakeChannel() {
        contextRunner
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=kafka",
                        "app.events.external.kafka.bootstrap-servers=localhost:9092",
                        "app.outbox.wake.enabled=true")
                .run(context -> {
                    // The subscription is assembled for the bootstrap (started
                    // on ApplicationReadyEvent); wake enablement must not break
                    // or replace the relay wiring.
                    assertThat(context).hasSingleBean(OutboxRelayBootstrap.class);
                    assertThat(context).hasSingleBean(OutboxWakePublisher.class);
                });
    }
}
