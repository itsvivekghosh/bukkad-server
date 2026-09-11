package com.bhukkad.common.integration;

import com.bhukkad.common.AbstractPostgresIntegrationTest;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.kafka.KafkaProperties;
import com.bhukkad.common.outbox.DeadLetterEventRepository;
import com.bhukkad.common.outbox.DeadLetterEventService;
import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.common.outbox.OutboxPollPublisher;
import com.bhukkad.common.outbox.OutboxProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the two-phase {@link OutboxPollPublisher} against
 * real PostgreSQL + real Redpanda: a PENDING row is claimed inside a real
 * transaction ({@code FOR UPDATE SKIP LOCKED} + batched PROCESSING flip),
 * published OUTSIDE the tx through {@link KafkaPlatformEventPublisher}, and
 * flipped to PUBLISHED in a second short batched transaction.
 *
 * <p>A publish failure (dead broker port) now leaves the row re-queueable as
 * PENDING with a bumped {@code retry_count} and a future
 * {@code next_attempt_at} backoff (PERF-2/D1 — the old code stranded rows in
 * PROCESSING and had no backoff at all).</p>
 *
 * <p>Skips cleanly when Docker is unavailable, matching
 * {@link AbstractPostgresIntegrationTest}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = com.bhukkad.common.PlatformTestConfig.class)
class OutboxPollPublisherIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final RedpandaContainer REDPANDA;
    private static final String TOPIC = "bhukkad.platform.events";

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Redpanda integration test");
        }
        REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.14");
        REDPANDA.start();
        // No eager topic creation: the Redpanda testcontainer module starts with
        // auto_create_topics_enabled=true, so the first publish creates the topic
        // lazily. An eager AdminClient round-trip in a static initializer made the
        // whole class unbootable whenever the broker was slow electing a
        // controller (flaky ExceptionInInitializerError for every test here).
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private DeadLetterEventRepository deadLetterRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM dead_letter_events");
    }

    @Test
    void endToEnd_claimPublishPersisted() {
        insertPending(PlatformEventMessage.of("OrderCreated", "42", "{\"id\":42}"));

        OutboxPollPublisher relay = buildRelay(true);
        int published = relay.drainBatch();

        assertThat(published).isEqualTo(1);
        OutboxEvent row = repository.findById(onlyId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(row.getNextAttemptAt()).isNull();

        // And the message actually landed on the topic.
        ConsumerRecord<String, String> record = consumeOne(TOPIC);
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo("42");
        assertThat(record.value()).contains("\"eventType\":\"OrderCreated\"");
    }

    @Test
    void endToEnd_noBroker_backsOffRequeueableRow() {
        insertPending(PlatformEventMessage.of("OrderCreated", "42", "{\"id\":42}"));

        // Publisher pointing at a port that nothing listens on.
        OutboxPollPublisher relay = buildRelay(false);
        LocalDateTime drainStartedAt = LocalDateTime.now();
        int published = relay.drainBatch();

        assertThat(published).isZero();
        OutboxEvent row = repository.findById(onlyId()).orElseThrow();
        // PERF-2: failed publish is re-queued to PENDING with retry_count bumped
        // and an exponential next_attempt_at, not stranded in PROCESSING.
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfterOrEqualTo(drainStartedAt);
        assertThat(row.getLastError()).isNotNull();
    }

    @Test
    void endToEnd_retryExhaustion_movesRowToDeadLetterTable() {
        OutboxEvent row = insertPending(PlatformEventMessage.of("OrderCreated", "42", "{\"id\":42}"));
        // One remaining attempt below the cap: this failed publish kills it.
        jdbcTemplate.update("UPDATE outbox_events SET retry_count = ?, next_attempt_at = NULL " +
                "WHERE id = ?",
                OutboxProperties.defaults().maxRetries() - 1, row.getId());
        // Out-of-band JDBC write: drop the stale managed instance so the claim
        // SELECT materialises the row with retry_count already bumped.
        entityManager.clear();

        OutboxPollPublisher relay = buildRelay(false);
        relay.drainBatch();

        OutboxEvent after = repository.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
        assertThat(deadLetterRepository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM dead_letter_events LIMIT 1", String.class)).isEqualTo("PENDING");
    }

    @Test
    void endToEnd_recoverStale_requeuesStrandedProcessingRows() {
        OutboxEvent row = insertPending(PlatformEventMessage.of("OrderCreated", "42", "{\"id\":42}"));
        // Simulate a crashed relay replica: PROCESSING started before the timeout.
        jdbcTemplate.update("UPDATE outbox_events SET status='PROCESSING', " +
                "processing_started_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(5), row.getId());
        entityManager.clear();

        OutboxPollPublisher relay = buildRelay(true);
        int recovered = relay.recoverStale();

        assertThat(recovered).isEqualTo(1);
        OutboxEvent after = repository.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(after.getProcessingStartedAt()).isNull();
    }

    private OutboxPollPublisher buildRelay(boolean withBroker) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        DeadLetterEventService deadLetters =
                new DeadLetterEventService(deadLetterRepository, repository);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        if (withBroker) {
            KafkaProperties props = new KafkaProperties(true, TOPIC, "it-group");
            Map<String, Object> cfg = Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class);
            KafkaTemplate<String, String> template =
                    new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
            KafkaPlatformEventPublisher publisher =
                    new KafkaPlatformEventPublisher(template, props, Duration.ofSeconds(20));
            return new OutboxPollPublisher(repository, publisher, OutboxProperties.defaults(),
                    tx, deadLetters, registry);
        }
        // Enabled publisher pointed at a dead port: the real send attempt times
        // out / errors, so publishForResult returns false and the row is
        // re-queued PENDING with backoff (or dead-lettered at maxRetries).
        KafkaProperties props = new KafkaProperties(true, TOPIC, "it-dead-group");
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
        return new OutboxPollPublisher(repository, publisher, OutboxProperties.defaults(),
                tx, deadLetters, registry);
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
            // Loop across poll windows: topic auto-creation + group join can
            // legitimately exceed a single 10s poll on a loaded dev machine.
            java.util.List<ConsumerRecord<String, String>> seen = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (seen.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofSeconds(5)).forEach(seen::add);
            }
            assertThat(seen).as("a record was published to %s".formatted(topic)).isNotEmpty();
            return seen.get(0);
        }
    }

    private Long onlyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_events LIMIT 1", Long.class);
    }

    private OutboxEvent insertPending(PlatformEventMessage message) {
        OutboxEvent e = new OutboxEvent();
        e.setEventType(message.eventType());
        e.setAggregateType("ORDER");
        e.setAggregateId(42L);
        e.setPayload(message.toJson());
        e.setStatus(OutboxEvent.OutboxStatus.PENDING);
        return repository.saveAndFlush(e);
    }
}
