package com.bhukkad.common.integration;

import com.bhukkad.common.AbstractPostgresIntegrationTest;
import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the platform outbox claim against a real PostgreSQL 16 database:
 * {@code FOR UPDATE SKIP LOCKED} claim semantics, batch limits, stale-processing
 * recovery, and exactly-once claiming under concurrent poller replicas.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = com.bhukkad.common.PlatformTestConfig.class)
class OutboxPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int EVENTS = 40;
    private static final int WORKERS = 8;
    private static final int BATCH = 5;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void claimPending_returnsOnlyPendingRows() {
        insertEvent("OrderCreated", OutboxEvent.OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(5));
        insertEvent("OrderStatusChanged", OutboxEvent.OutboxStatus.PUBLISHED, LocalDateTime.now().minusMinutes(5));

        List<OutboxEvent> claimed = repository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 50, LocalDateTime.now());

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getEventType()).isEqualTo("OrderCreated");
    }

    @Test
    void claim_respectsBatchLimit() {
        for (int i = 0; i < 10; i++) {
            insertEvent("OrderCreated", OutboxEvent.OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(5 + i));
        }

        List<OutboxEvent> claimed = repository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 3, LocalDateTime.now());

        assertThat(claimed).hasSize(3);
    }

    @Test
    void claim_returnsOldestFirst() {
        insertRawEvent("OrderCreated", LocalDateTime.now().minusMinutes(30));
        insertRawEvent("OrderStatusChanged", LocalDateTime.now().minusMinutes(10));
        insertRawEvent("OrderDelivered", LocalDateTime.now().minusMinutes(20));

        List<OutboxEvent> claimed = repository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 50, LocalDateTime.now());

        assertThat(claimed).extracting(OutboxEvent::getEventType)
                .containsExactly("OrderCreated", "OrderDelivered", "OrderStatusChanged");
    }

    @Test
    void staleProcessingEvents_areFoundByQuery() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        OutboxEvent stale = insertEvent("OrderCreated", OutboxEvent.OutboxStatus.PROCESSING,
                LocalDateTime.now().minusHours(1));
        OutboxEvent fresh = insertEvent("OrderStatusChanged", OutboxEvent.OutboxStatus.PROCESSING,
                LocalDateTime.now());

        List<OutboxEvent> staleEvents = repository.findStaleProcessing(
                OutboxEvent.OutboxStatus.PROCESSING, cutoff);

        assertThat(staleEvents).extracting(OutboxEvent::getId)
                .contains(stale.getId())
                .doesNotContain(fresh.getId());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void concurrentClaim_eachEventClaimedExactlyOnce() throws Exception {
        for (int i = 0; i < EVENTS; i++) {
            insertEvent("OrderCreated", OutboxEvent.OutboxStatus.PENDING,
                    LocalDateTime.now().minusMinutes(EVENTS - i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Set<Long>> perWorkerClaims = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int w = 0; w < WORKERS; w++) {
                final Set<Long> claimedById = new HashSet<>();
                perWorkerClaims.add(claimedById);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    while (true) {
                        int claimed = tx.execute(status -> {
                            List<OutboxEvent> batch = repository.findPendingForProcessing(
                                    OutboxEvent.OutboxStatus.PENDING.name(), BATCH, LocalDateTime.now());
                            for (OutboxEvent e : batch) {
                                e.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
                                e.setProcessingStartedAt(LocalDateTime.now());
                                claimedById.add(e.getId());
                            }
                            repository.flush();
                            return batch.size();
                        });
                        if (claimed == 0) {
                            break;
                        }
                    }
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            List<Long> allClaims = perWorkerClaims.stream().flatMap(Set::stream).toList();
            assertThat(allClaims).hasSize(EVENTS);
            assertThat(allClaims).doesNotHaveDuplicates();
            assertThat(repository.countByStatus(OutboxEvent.OutboxStatus.PENDING)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private OutboxEvent insertEvent(String eventType, OutboxEvent.OutboxStatus status, LocalDateTime createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType("ORDER");
        event.setAggregateId(1L);
        event.setPayload("{\"test\":true}");
        event.setStatus(status);
        event.setCreatedAt(createdAt);
        if (status == OutboxEvent.OutboxStatus.PROCESSING) {
            event.setProcessingStartedAt(createdAt);
        }
        return repository.saveAndFlush(event);
    }

    private void insertRawEvent(String eventType, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO outbox_events (event_type, aggregate_type, aggregate_id, payload, " +
                        "status, retry_count, created_at) VALUES (?, ?, ?, ?, 'PENDING', 0, ?)",
                eventType, "ORDER", 1L, "{\"test\":true}", createdAt);
    }
}
