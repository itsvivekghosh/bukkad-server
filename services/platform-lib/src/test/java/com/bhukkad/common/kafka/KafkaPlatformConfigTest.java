package com.bhukkad.common.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

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

    @Test
    void isKafkaEnabled_reflectsEnabledAndType() {
        assertThat(KafkaPlatformProperties.disabled().isKafkaEnabled()).isFalse();

        KafkaPlatformProperties enabledKafka = new KafkaPlatformProperties(true, "kafka",
                new KafkaPlatformProperties.Kafka("localhost:9092", "g", "t", "dlt"));
        assertThat(enabledKafka.isKafkaEnabled()).isTrue();

        KafkaPlatformProperties enabledLog = new KafkaPlatformProperties(true, "log",
                new KafkaPlatformProperties.Kafka("localhost:9092", "g", "t", "dlt"));
        assertThat(enabledLog.isKafkaEnabled()).isFalse();

        KafkaPlatformProperties disabledKafka = new KafkaPlatformProperties(false, "kafka",
                new KafkaPlatformProperties.Kafka("localhost:9092", "g", "t", "dlt"));
        assertThat(disabledKafka.isKafkaEnabled()).isFalse();
    }
}
