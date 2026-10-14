package com.bhukkad.admin;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Collections;

/**
 * Kafka test doubles shared by every admin test context ({@code
 * AbstractAdminPostgresTest} imports it).
 *
 * <p>The admin test {@code application.yml} runs with the DLQ backbone
 * switched on so the management endpoints keep their production wiring.
 * This configuration registers an inert mocked {@link KafkaConsumer}
 * under the canonical bean name {@value #DLQ_CONSUMER_BEAN_NAME}: because
 * {@link com.bhukkad.admin.kafka.dlq.DlqConsumerConfig#dlqKafkaConsumer}
 * carries {@code @ConditionalOnMissingBean(name = "dlqKafkaConsumer")},
 * no real consumer is ever constructed in a test, so context startup never
 * attempts a broker connection and every DLQ accessor degrades to an empty
 * result.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestKafkaTestConfig {

    /** Bean name the production DLQ consumer is registered under. */
    static final String DLQ_CONSUMER_BEAN_NAME = "dlqKafkaConsumer";

    /**
     * Inert stand-in for the DLQ consumer: {@code assignment()} is always
     * empty, polls return no records and no offset lookup can reach a
     * broker.
     */
    @SuppressWarnings("unchecked")
    @Bean(name = DLQ_CONSUMER_BEAN_NAME)
    public KafkaConsumer<String, String> dlqKafkaConsumer() {
        KafkaConsumer<String, String> mock = Mockito.mock(KafkaConsumer.class);
        Mockito.when(mock.assignment()).thenReturn(Collections.emptySet());
        Mockito.when(mock.poll(Mockito.any(java.time.Duration.class)))
                .thenReturn(new ConsumerRecords<>(Collections.emptyMap()));
        return mock;
    }
}
