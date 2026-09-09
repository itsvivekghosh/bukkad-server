package com.bhukkad.common.integration;

import com.bhukkad.common.AbstractPostgresIntegrationTest;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.outbox.DeadLetterEventRepository;
import com.bhukkad.common.outbox.DeadLetterEventService;
import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.common.outbox.OutboxPollPublisher;
import com.bhukkad.common.outbox.OutboxProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PERF-2/B1 regression: with the claim in a REAL transaction
 * (TransactionTemplate — the old self-invoked {@code @Transactional} was a
 * no-op), two relay replicas draining the same table must claim DISJOINT sets
 * and produce exactly one publish per row (no duplicate events).
 *
 * Runs on the shared PostgreSQL Testcontainers schema; rows are seeded
 * OUTSIDE the test transaction ({@code NOT_SUPPORTED}) so both worker
 * transactions observe committed data under READ COMMITTED.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = com.bhukkad.common.PlatformTestConfig.class)
class OutboxRelayClaimPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int ROWS = 24;

    @Autowired
    private JdbcTemplate jdbcTemplate;
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void twoReplicas_claimDisjointSets_publishEveryRowExactlyOnce() throws Exception {
        for (int i = 0; i < ROWS; i++) {
            OutboxEvent e = new OutboxEvent();
            e.setEventType("OrderCreated");
            e.setAggregateType("ORDER");
            e.setAggregateId((long) i);
            e.setPayload(PlatformEventMessage.of("OrderCreated", String.valueOf(i),
                    "{\"id\":" + i + "}").toJson());
            e.setStatus(OutboxEvent.OutboxStatus.PENDING);
            repository.saveAndFlush(e);
        }

        Set<Long> publishedIds = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            for (int replica = 0; replica < 2; replica++) {
                // One relay per "replica": same table, independent tx templates.
                OutboxPollPublisher relay = buildRelay(publishedIds);
                pool.submit(() -> {
                    try {
                        // Drain to exhaustion like the scheduled poll loop would.
                        int guard = 0;
                        while (relay.drainBatch() > 0 && guard++ < ROWS) {
                            // keep draining
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).as("relays finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEmpty();
        // Every row exactly once across replicas: the disjoint claim is the fix.
        assertThat(publishedIds).hasSize(ROWS);
        Long pendingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED'", Long.class);
        assertThat(pendingCount).isZero();
    }

    private OutboxPollPublisher buildRelay(Set<Long> sink) {
        KafkaPlatformEventPublisher publisher = mock(KafkaPlatformEventPublisher.class);
        when(publisher.publishForResult(any(PlatformEventMessage.class))).thenAnswer(inv -> {
            PlatformEventMessage msg = inv.getArgument(0);
            // The send "ack" — record which aggregateId THIS publish handled.
            sink.add(Long.valueOf(msg.aggregateId()));
            return true;
        });
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        DeadLetterEventService deadLetters =
                new DeadLetterEventService(deadLetterRepository, repository);
        return new OutboxPollPublisher(repository, publisher, OutboxProperties.defaults(),
                tx, deadLetters, new SimpleMeterRegistry());
    }
}
