package com.bhukkad.common.integration;

import com.bhukkad.common.AbstractPostgresIntegrationTest;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.kafka.KafkaProperties;
import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.common.outbox.OutboxPollPublisher;
import com.bhukkad.common.outbox.OutboxProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end verification of {@link OutboxPollPublisher}: a PENDING row is
 * claimed (real PostgreSQL with {@code FOR UPDATE SKIP LOCKED}), published to a
 * real broker (Redpanda via Testcontainers) through
 * {@link KafkaPlatformEventPublisher}, and flipped to PUBLISHED. A publish
 * failure (no broker) leaves the row re-queueable (PROCESSING, retry bumped).
 *
 * <p>Skips cleanly when Docker is unavailable, matching
 * {@link AbstractPostgresIntegrationTest}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxPollPublisherIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final RedpandaContainer REDPANDA;
    private static final String TOPIC = "bhukkad.ordercreated";

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Redpanda integration test");
        }
        REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.14");
        REDPANDA.start();
        createTopic(TOPIC);
    }

    private static void createTopic(String topic) {
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 1, (short) 1)))
                    .all().get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OutboxEventRepository repository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void endToEnd_claimPublishPersisted() {
        insertPending(PlatformEventMessage.of("OrderCreated", "42", "{\"id\":42}"));

        OutboxPollPublisher relay = buildRelay(true);
        int published = relay.drainBatch();

        assertThat(published).isEqualTo(1);
        OutboxEvent row = repository.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();

        // And the message actually landed on the topic.
        ConsumerRecord<String, String> record = consumeOne(TOPIC);
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo("42");
        assertThat(record.value()).contains("\"eventType\":\"OrderCreated\"");
    }

    @Test
    void endToEnd_noBroker_publishesNothingAndLeavesRequeueable() {
        insertPending(PlatformEventMessage.of("OrderCreated", "42", "{\"id\":42}"));

        // Publisher pointing at a port that nothing listens on.
        OutboxPollPublisher relay = buildRelay(false);
        int published = relay.drainBatch();

        assertThat(published).isZero();
        OutboxEvent row = repository.findAll().get(0);
        // Failed sends keep the row PROCESSING so recoverStale() can re-queue it.
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PROCESSING);
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getLastError()).isNotNull();
    }

    private OutboxPollPublisher buildRelay(boolean withBroker) {
        if (withBroker) {
            KafkaProperties props = new KafkaProperties(true, "bhukkad.", "it-group");
            Map<String, Object> cfg = Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class);
            KafkaTemplate<String, String> template =
                    new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
            KafkaPlatformEventPublisher publisher =
                    new KafkaPlatformEventPublisher(template, props, Duration.ofSeconds(5));
            return new OutboxPollPublisher(repository, publisher, OutboxProperties.defaults());
        }
        // Enabled publisher pointed at a dead port: the real send attempt times
        // out / errors, so publishForResult returns false and the row stays
        // PROCESSING (re-queueable) with retryCount bumped.
        KafkaProperties props = new KafkaProperties(true, "bhukkad.", "it-dead-group");
        Map<String, Object> cfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 100,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 100);
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg)),
                props, Duration.ofMillis(200));
        return new OutboxPollPublisher(repository, publisher, OutboxProperties.defaults());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ConsumerRecord<String, String> consumeOne(String topic) {
        Properties p = new Properties();
        p.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                REDPANDA.getBootstrapServers());
        p.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "it-consumer");
        p.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        p.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.iterator().hasNext()).as("a record was published to %s".formatted(topic)).isTrue();
            return records.iterator().next();
        }
    }

    private void insertPending(PlatformEventMessage message) {
        OutboxEvent e = new OutboxEvent();
        e.setEventType(message.eventType());
        e.setAggregateType("ORDER");
        e.setAggregateId(42L);
        e.setPayload(message.toJson());
        e.setStatus(OutboxEvent.OutboxStatus.PENDING);
        repository.saveAndFlush(e);
    }
}
