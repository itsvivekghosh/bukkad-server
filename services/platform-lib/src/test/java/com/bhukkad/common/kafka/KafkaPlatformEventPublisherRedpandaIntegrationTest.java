package com.bhukkad.common.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end verification of {@link KafkaPlatformEventPublisher} against a real
 * Redpanda broker (Kafka-compatible) via Testcontainers.
 *
 * <p>Skips cleanly ({@link TestAbortedException}) when Docker is unavailable,
 * following the same pattern as {@link com.bhukkad.common.AbstractPostgresIntegrationTest}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaPlatformEventPublisherRedpandaIntegrationTest {

    static final RedpandaContainer REDPANDA = new RedpandaContainer(
            "docker.redpanda.com/redpandadata/redpanda:v23.3.14");

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Redpanda integration test");
        }
        REDPANDA.start();
    }

    @Test
    void publishesAndConsumesEnvelope() throws Exception {
        // Base platform topic (publisher/consumer parity): the publisher must
        // write the topic exactly as configured, without any per-type suffix.
        String topic = "bhukkad.platform.events";
        createTopic(topic);

        Map<String, Object> producerConfig = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> kafkaTemplate =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerConfig));

        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, topic, "redpanda-it-group"));

        PlatformEventMessage message = PlatformEventMessage.of("OrderCreated", "42", "{\"status\":\"PLACED\"}");
        publisher.publish(message);
        kafkaTemplate.flush();

        ConsumerRecord<String, String> consumed = consumeAndVerify(topic);
        assertThat(consumed.key()).isEqualTo("42");
        assertThat(consumed.value()).contains("\"eventType\":\"OrderCreated\"");
        assertThat(consumed.value()).contains("\"aggregateId\":\"42\"");
    }

    private void createTopic(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    private ConsumerRecord<String, String> consumeAndVerify(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "redpanda-it-consumer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            consumer.poll(Duration.ofMillis(200)); // join group before publish

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.topic().equals(topic) && "42".equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return fail("No matching record consumed from Redpanda within 30s");
    }
}