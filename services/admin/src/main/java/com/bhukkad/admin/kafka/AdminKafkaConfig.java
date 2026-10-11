package com.bhukkad.admin.kafka;

import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.kafka.KafkaPlatformProperties;
import com.bhukkad.common.kafka.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal Kafka configuration for admin service DLQ consumer.
 *
 * <p>Provides only the beans needed for the DLQ consumer without enabling
 * full Kafka auto-configuration. This avoids pulling in the entire Kafka
 * stack (listeners, transactions, etc.) which the admin service doesn't need.</p>
 *
 * <p>Beans are only created when {@code app.events.external.enabled=true} and
 * {@code app.events.external.type=kafka} and {@code app.events.external.dlq.enabled=true}.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaPlatformProperties.class)
public class AdminKafkaConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.events.external", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external", name = "type", havingValue = "kafka", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external.dlq", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnMissingBean
    public ProducerFactory<String, String> dlqProducerFactory(KafkaPlatformProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.events.external", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external", name = "type", havingValue = "kafka", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external.dlq", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnMissingBean
    public KafkaTemplate<String, String> dlqKafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.events.external", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external", name = "type", havingValue = "kafka", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external.dlq", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnMissingBean
    public KafkaProperties dlqKafkaProperties(KafkaPlatformProperties properties) {
        return new KafkaProperties(properties.enabled(), properties.kafka().platformTopic(), 
                "admin-dlq-consumer");
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.events.external", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external", name = "type", havingValue = "kafka", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external.dlq", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnMissingBean
    public ConsumerFactory<String, String> dlqConsumerFactory(KafkaPlatformProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "admin-dlq-consumer");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.events.external", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external", name = "type", havingValue = "kafka", matchIfMissing = false)
    @ConditionalOnProperty(prefix = "app.events.external.dlq", name = "enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnMissingBean(name = "dlqKafkaConsumer")
    public KafkaConsumer<String, String> dlqKafkaConsumer(ConsumerFactory<String, String> consumerFactory,
                                                          KafkaPlatformProperties properties) {
        var consumer = (KafkaConsumer<String, String>) consumerFactory.createConsumer("admin-dlq-consumer");
        
        // Subscribe to the DLQ topic for this service
        String baseTopic = properties.kafka().platformTopic();
        String dlqTopic = baseTopic + ".dlt";
        consumer.subscribe(Collections.singletonList(dlqTopic));
        
        return consumer;
    }
}