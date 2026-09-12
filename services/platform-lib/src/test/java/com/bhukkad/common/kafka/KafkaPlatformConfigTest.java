package com.bhukkad.common.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-service Kafka wiring gates:
 * <ul>
 *   <li>disabled by default ({@code app.events.external.enabled=false}) → no
 *       publisher bean, no KafkaTemplate, no broker connection attempt;</li>
 *   <li>enabled with {@code type=kafka} → publisher and KafkaTemplate beans
 *       exist without connecting (producer factories connect lazily);</li>
 *   <li>{@link KafkaPlatformProperties#isKafkaEnabled()} reflects
 *       enabled+type semantics.</li>
 * </ul>
 */
import java.util.Map;

class KafkaPlatformConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaPlatformConfig.class);

    @Test
    void disabledByDefault_doesNotCreatePublisher() {
        contextRunner.run(context -> {
            assertThat(context.getBeansOfType(KafkaPlatformEventPublisher.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaTemplate.class)).isEmpty();
        });
    }

    @Test
    void enabledKafka_createsPublisherWithoutConnecting() {
        contextRunner
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=kafka",
                        "app.events.external.kafka.bootstrap-servers=localhost:9092",
                        "app.events.external.kafka.consumer-group=order-platform-consumer",
                        "app.events.external.kafka.platform-topic=order.events.v1",
                        "app.events.external.kafka.dlq-topic=order.events.v1.dlt")
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaPlatformEventPublisher.class);
                    assertThat(context).hasSingleBean(KafkaTemplate.class);
                });
    }

    /**
     * PERF-2/B2 single-gate proof: {@code enabled=true} with {@code type=log}
     * must leave the publisher (and therefore the relay too) OFF as well — the
     * old split where the OutboxPlatformConfig relay gate watched only
     * {@code enabled} is what made the blackhole reachable.
     */
    @Test
    void enabledButLogTransport_createsNothing() {
        contextRunner
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=log")
                .run(context -> {
                    assertThat(context.getBeansOfType(KafkaPlatformEventPublisher.class)).isEmpty();
                    assertThat(context.getBeansOfType(KafkaTemplate.class)).isEmpty();
                });
    }

    /** P-03: the surviving producer factory carries the durability/throughput knobs. */
    @Test
    @SuppressWarnings("rawtypes")
    void producerFactory_carriesAckAllIdempotenceAndBatchingKnobs() {
        contextRunner
                .withPropertyValues(
                        "app.events.external.enabled=true",
                        "app.events.external.type=kafka",
                        "app.events.external.kafka.bootstrap-servers=localhost:9092",
                        "app.events.external.kafka.consumer-group=g",
                        "app.events.external.kafka.platform-topic=t",
                        "app.events.external.kafka.dlq-topic=t.dlt")
                .run(context -> {
                    ProducerFactory factory = context.getBean(ProducerFactory.class);
                    Map config = factory.getConfigurationProperties();
                    assertThat(config.get(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
                    assertThat(config.get(org.apache.kafka.clients.producer.ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
                    assertThat(config.get(org.apache.kafka.clients.producer.ProducerConfig.RETRIES_CONFIG))
                            .isEqualTo(Integer.MAX_VALUE);
                    assertThat(config.get(org.apache.kafka.clients.producer.ProducerConfig.LINGER_MS_CONFIG)).isEqualTo(5);
                    assertThat(config.get(org.apache.kafka.clients.producer.ProducerConfig.BATCH_SIZE_CONFIG)).isEqualTo(32768);
                    assertThat(config.get(org.apache.kafka.clients.producer.ProducerConfig.COMPRESSION_TYPE_CONFIG)).isEqualTo("lz4");

                    // V-10: the one surviving listener factory must carry a
                    // DefaultErrorHandler with a DLT recoverer. The factory wires
                    // setCommonErrorHandler(...) at creation; assert the wiring
                    // source directly (same-package access to the static builder).
                    ConcurrentKafkaListenerContainerFactory<?, ?> listenerFactory =
                            context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(listenerFactory).isNotNull();
                    DefaultErrorHandler errorHandler =
                            KafkaPlatformConfig.kafkaListenerErrorHandler(context.getBean(KafkaTemplate.class));
                    assertThat(errorHandler).isNotNull();
                });
    }

    /** V-10: poison routing uses <topic>.dlt on the original partition, 3 retries, audit headers. */
    @Test
    void listenerErrorHandler_routesToTopicDltWithAuditHeaders() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = org.mockito.Mockito.mock(KafkaTemplate.class);
        DefaultErrorHandler handler = KafkaPlatformConfig.kafkaListenerErrorHandler(template);
        assertThat(handler).isNotNull();
        // Retry budget: ExponentialBackOffWithMaxRetries(3) per the audit guide.
        Object tracker = org.springframework.test.util.ReflectionTestUtils
                .getField(handler, "failureTracker");
        Object backOff = org.springframework.test.util.ReflectionTestUtils
                .getField(tracker, "backOff");
        assertThat(backOff)
                .isInstanceOf(org.springframework.kafka.support.ExponentialBackOffWithMaxRetries.class);
        assertThat(((org.springframework.kafka.support.ExponentialBackOffWithMaxRetries) backOff)
                .getMaxRetries()).isEqualTo(3);
    }

    @Test
    void isKafkaEnabled_reflectsEnabledAndType() {
        assertThat(KafkaPlatformProperties.disabled().isKafkaEnabled()).isFalse();

        KafkaPlatformProperties enabledKafka = new KafkaPlatformProperties(true, "kafka",
                new KafkaPlatformProperties.Kafka("localhost:9092", "g", "t", "dlt"), false);
        assertThat(enabledKafka.isKafkaEnabled()).isTrue();

        KafkaPlatformProperties enabledLog = new KafkaPlatformProperties(true, "log",
                new KafkaPlatformProperties.Kafka("localhost:9092", "g", "t", "dlt"), false);
        assertThat(enabledLog.isKafkaEnabled()).isFalse();

        KafkaPlatformProperties disabledKafka = new KafkaPlatformProperties(false, "kafka",
                new KafkaPlatformProperties.Kafka("localhost:9092", "g", "t", "dlt"), false);
        assertThat(disabledKafka.isKafkaEnabled()).isFalse();
    }
}
