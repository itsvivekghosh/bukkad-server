package com.bhukkad.admin.kafka.dlq;

import com.bhukkad.common.kafka.KafkaPlatformProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka infrastructure for the DLQ tooling.
 *
 * <p>The admin service has no Kafka producers of its own — it only *inspects*
 * and *re-drives* the dead-letter topics produced by the commerce service.
 * Therefore this configuration deliberately wires a bare
 * {@link KafkaConsumer} instead of enabling full Spring Kafka
 * auto-configuration (which would pull listeners, transactional producers and
 * container management the service does not need).</p>
 *
 * <p>Everything here is gated on {@link KafkaDlqCondition}
 * ({@code app.events.external.enabled=true} AND {@code type=kafka} AND
 * {@code app.events.external.dlq.enabled=true}). When the backbone is disabled
 * — the default, and every unit/integration test — no Kafka bean is created,
 * so the DLQ services degrade to a no-op instead of failing context startup.</p>
 *
 * <p>The consumer uses the group {@code admin-dlq-consumer}, separate from the
 * production consumer groups, so inspecting the DLQ never interferes with the
 * main event pipeline.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaPlatformProperties.class)
@Conditional(KafkaDlqCondition.class)
@RequiredArgsConstructor
@Slf4j
public class DlqConsumerConfig {

    @Bean
    @ConditionalOnMissingBean
    public ConsumerFactory<String, String> dlqConsumerFactory(KafkaPlatformProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "admin-dlq-consumer");
        // The DLQ consumer only reads and commits offsets after the operator
        // has seen a record, so auto-commit must stay off.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dlqKafkaConsumer")
    public KafkaConsumer<String, String> dlqKafkaConsumer(ConsumerFactory<String, String> consumerFactory,
                                                          KafkaPlatformProperties properties) {
        var consumer = (KafkaConsumer<String, String>) consumerFactory.createConsumer("admin-dlq-consumer");

        String baseTopic = properties.kafka().platformTopic();
        String dlqTopic = baseTopic + ".dlt";
        consumer.subscribe(Collections.singletonList(dlqTopic));

        log.info("DLQ consumer subscribed to: {}", dlqTopic);
        return consumer;
    }
}
