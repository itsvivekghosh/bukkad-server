package com.bhukkad.common.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-service Kafka wiring for the platform event pipeline
 * (architecture-microservices-postgresql.md §6.2).
 *
 * <p><strong>Design decision (documented):</strong> the entire Kafka stack —
 * {@link ProducerFactory}, {@link KafkaTemplate} and
 * {@link KafkaPlatformEventPublisher} — is created <em>only when</em>
 * {@code app.events.external.enabled=true}. The {@code @ConditionalOnProperty}
 * gate is evaluated from class metadata, so disabled services never load this
 * class, never create a KafkaTemplate and never attempt a broker connection
 * (including {@code KafkaAutoConfiguration} backoff). {@code @ConditionalOnClass}
 * additionally skips the wiring when spring-kafka is not on the consuming
 * service's classpath (it is an optional platform-lib dependency); a service
 * that enables Kafka must therefore declare spring-kafka itself.</p>
 *
 * <p>The {@link KafkaPlatformEventPublisher} receives the existing
 * {@link KafkaProperties} record: {@code topicPrefix} is derived from
 * {@code app.events.external.kafka.platform-topic} (a trailing {@code .} is
 * appended so the publisher produces {@code <platform-topic>.<eventType>}) and
 * {@code groupId} from {@code app.events.external.kafka.consumer-group}. The
 * publisher's internal {@code enabled} flag mirrors {@link KafkaPlatformProperties#isKafkaEnabled()}
 * so {@code type: log} yields a no-op publisher.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(KafkaPlatformProperties.class)
@ConditionalOnProperty(name = "app.events.external.enabled", havingValue = "true")
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class KafkaPlatformConfig {

    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    public ProducerFactory<String, String> kafkaProducerFactory(KafkaPlatformProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate.class)
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public KafkaPlatformEventPublisher kafkaPlatformEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                                                   KafkaPlatformProperties properties) {
        KafkaPlatformProperties.Kafka kafka = properties.kafka();
        String topicPrefix = kafka.platformTopic() + ".";
        String groupId = kafka.consumerGroup();
        return new KafkaPlatformEventPublisher(kafkaTemplate,
                new KafkaProperties(properties.isKafkaEnabled(), topicPrefix, groupId));
    }
}
