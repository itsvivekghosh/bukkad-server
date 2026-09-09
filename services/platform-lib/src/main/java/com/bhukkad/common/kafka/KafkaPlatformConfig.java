package com.bhukkad.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

/**
 * The single Spring Kafka wiring for every microservice (PERF-2/P-03/R-B).
 *
 * <p>The duplicate legacy {@code common.config.KafkaConfig} (manual
 * producer/listener factories that hijacked the {@code kafkaTemplate} /
 * {@code kafkaListenerContainerFactory} bean names whenever
 * {@code type=kafka} — with {@code max.in.flight=5} under idempotence,
 * {@code MANUAL_IMMEDIATE} acks no listener ever performed, and no DLT
 * handling) has been deleted; this factory is now the only Kafka wiring in
 * the platform.</p>
 *
 * <p><strong>Single gate (B2):</strong> the {@code @ConditionalOnExpression}
 * ({@code app.events.external.enabled=true} AND
 * {@code app.events.external.type=kafka}) is byte-identical to the gate on
 * {@link com.bhukkad.common.outbox.OutboxPlatformConfig} — the relay and the
 * publisher come up or stay down together.</p>
 *
 * <p>Producer durability/tuning (P-03): {@code acks=all},
 * {@code enable.idempotence=true}, retries unbounded, {@code linger.ms=5},
 * {@code batch.size=32768}, {@code compression.type=lz4}.</p>
 *
 * <p><strong>Consumer error handling (V-10):</strong> the listener container
 * factory carries a {@link DefaultErrorHandler} that retries
 * {@value #MAX_RETRY_ATTEMPTS} times with exponential backoff and then routes
 * the poison record to {@code <topic>.dlt} via
 * {@link DeadLetterPublishingRecoverer}, stamping the audit-required
 * {@code x-failed-topic} / {@code x-error-class} headers on top of the
 * standard {@code kafka_dlt-*} originals. Listener bodies must no longer
 * blanket-catch: an exception is what routes a poison event to the DLT
 * instead of committing it into oblivion.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(KafkaPlatformProperties.class)
@ConditionalOnExpression(
        "'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka'")
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class KafkaPlatformConfig {

    static final int MAX_RETRY_ATTEMPTS = 3;
    static final String HEADER_FAILED_TOPIC = "x-failed-topic";
    static final String HEADER_ERROR_CLASS = "x-error-class";

    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    public ProducerFactory<String, String> kafkaProducerFactory(KafkaPlatformProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // P-03: money-event durability + throughput knobs (idempotence keeps
        // exactly-once produce semantics across the unbounded retries).
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
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

    // ── Consumer infrastructure ──────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(ConsumerFactory.class)
    public ConsumerFactory<String, String> kafkaConsumerFactory(KafkaPlatformProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.kafka().consumerGroup());
        // Offsets are committed by the container AFTER the listener (or the
        // error handler's DLT hand-off) completes — never auto-committed under
        // a blanket catch that would ack poison events (V-10).
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaListenerErrorHandler(kafkaTemplate));
        return factory;
    }

    /**
     * V-10: poison events retry {@value #MAX_RETRY_ATTEMPTS} times, then land on
     * {@code <topic>.dlt} on the same partition with the original record and
     * {@code x-failed-topic}/{@code x-error-class} headers (plus the standard
     * {@code kafka_dlt-*} originals).
     */
    static DefaultErrorHandler kafkaListenerErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".dlt", record.partition()));
        // ADDITIVE headers function: keeps DLPR's standard original headers and
        // stamps the audit-required failure markers.
        recoverer.addHeadersFunction((record, exception) -> {
            org.apache.kafka.common.header.Headers headers =
                    new org.apache.kafka.common.header.internals.RecordHeaders();
            headers.add(namedHeader(HEADER_FAILED_TOPIC, record.topic()));
            headers.add(namedHeader(HEADER_ERROR_CLASS,
                    exception == null ? "unknown" : exception.getClass().getName()));
            return headers;
        });
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(MAX_RETRY_ATTEMPTS);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000L);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    private static org.apache.kafka.common.header.Header namedHeader(String name, String value) {
        return new org.apache.kafka.common.header.internals.RecordHeader(
                name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
