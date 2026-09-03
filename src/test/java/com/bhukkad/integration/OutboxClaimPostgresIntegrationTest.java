package com.bhukkad.integration;

import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the outbox claim-and-process semantics against a real PostgreSQL 16
 * database — the PostgreSQL port of {@link OutboxClaimIntegrationTest}
 * (architecture-microservices-postgresql.md §12 P0: "port outbox claim ...
 * tests to PG").
 *
 * <p>The claim query uses {@code SELECT ... FOR UPDATE SKIP LOCKED}, which is
 * syntax-identical on MySQL and PostgreSQL. These tests prove the semantics
 * hold on PG READ COMMITTED under real concurrency: when several poller
 * replicas claim batches simultaneously, every event is claimed by exactly one
 * worker and none is stranded.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OutboxClaimPostgresIntegrationTest extends AbstractPostgresJpaIntegrationTest {

    private static final int EVENTS = 40;
    private static final int WORKERS = 8;
    private static final int BATCH = 5;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void claimPending_returnsOnlyPendingRows() {
        insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(5));
        insertEvent("ORDER_STATUS_CHANGED", OutboxEvent.OutboxStatus.PUBLISHED, LocalDateTime.now().minusMinutes(5));

        List<OutboxEvent> claimed = outboxEventRepository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 50);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getEventType()).isEqualTo("ORDER_CREATED");
    }

    @Test
    void claimAfterMarkingProcessing_returnsEmpty() {
        OutboxEvent event = insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PENDING,
                LocalDateTime.now().minusMinutes(5));
        event.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
        event.setProcessingStartedAt(LocalDateTime.now());
        outboxEventRepository.save(event);

        List<OutboxEvent> claimed = outboxEventRepository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 50);

        assertThat(claimed).isEmpty();
    }

    @Test
    void claim_respectsBatchLimit() {
        for (int i = 0; i < 10; i++) {
            insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PENDING,
                    LocalDateTime.now().minusMinutes(5 + i));
        }

        List<OutboxEvent> claimed = outboxEventRepository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 3);

        assertThat(claimed).hasSize(3);
    }

    @Test
    void claim_returnsOldestFirst() {
        // JPA auditing (@EnableJpaAuditing + @CreatedDate on OutboxEvent) stamps
        // createdAt at persist time, so ordering must be controlled at the JDBC
        // layer with explicit created_at values.
        insertRawEvent("ORDER_CREATED", LocalDateTime.now().minusMinutes(30));
        insertRawEvent("ORDER_STATUS_CHANGED", LocalDateTime.now().minusMinutes(10));
        insertRawEvent("ORDER_DELIVERED", LocalDateTime.now().minusMinutes(20));

        List<OutboxEvent> claimed = outboxEventRepository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 50);

        assertThat(claimed).extracting(OutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED", "ORDER_DELIVERED", "ORDER_STATUS_CHANGED");
    }

    @Test
    void staleProcessingEvents_areFoundByQuery() {
        LocalDateTime staleCutoff = LocalDateTime.now().minusMinutes(30);
        OutboxEvent stale = insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PROCESSING,
                LocalDateTime.now().minusHours(1));
        OutboxEvent fresh = insertEvent("ORDER_STATUS_CHANGED", OutboxEvent.OutboxStatus.PROCESSING,
                LocalDateTime.now());

        List<OutboxEvent> staleEvents = outboxEventRepository.findStaleProcessing(
                OutboxEvent.OutboxStatus.PROCESSING, staleCutoff);

        assertThat(staleEvents).extracting(OutboxEvent::getId)
                .contains(stale.getId())
                .doesNotContain(fresh.getId());
    }

    @Test
    void processingStartedAtColumnIsPersisted() {
        OutboxEvent event = insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PROCESSING, LocalDateTime.now());
        OutboxEvent readBack = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(readBack.getProcessingStartedAt()).isNotNull();
    }

    /**
     * Heavy-traffic guard: many poller replicas claim batches at the same time
     * (simulating N horizontally scaled instances). {@code FOR UPDATE SKIP
     * LOCKED} must guarantee each event is claimed by exactly one worker —
     * no double-claims, no stranded events — even when every worker targets the
     * same oldest rows first.
     *
     * <p>This method runs WITHOUT a test transaction (@DataJpaTest defaults to
     * {@code @Transactional}), so the 40 events inserted via the repository
     * commit immediately and are visible to the concurrent worker threads (each
     * of which uses its own {@code TransactionTemplate} transaction).</p>
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void concurrentClaim_eachEventClaimedExactlyOnce() throws Exception {
        for (int i = 0; i < EVENTS; i++) {
            insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PENDING,
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
                    AtomicInteger local = new AtomicInteger();
                    while (true) {
                        int claimed = tx.execute(status -> {
                            List<OutboxEvent> batch = outboxEventRepository.findPendingForProcessing(
                                    OutboxEvent.OutboxStatus.PENDING.name(), BATCH);
                            for (OutboxEvent e : batch) {
                                e.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
                                e.setProcessingStartedAt(LocalDateTime.now());
                                claimedById.add(e.getId());
                            }
                            outboxEventRepository.flush();
                            return batch.size();
                        });
                        local.addAndGet(claimed);
                        if (claimed == 0) {
                            break;
                        }
                    }
                    return local.get();
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            // Flatten every worker's claimed ids: the union must be exactly the
            // EVENTS events with no duplicates — proof that SKIP LOCKED let each
            // event be claimed by exactly one concurrent poller.
            List<Long> allClaims = perWorkerClaims.stream()
                    .flatMap(Set::stream)
                    .toList();
            assertThat(allClaims).hasSize(EVENTS);
            assertThat(allClaims).doesNotHaveDuplicates();
            assertThat(outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PENDING)).isZero();
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
        return outboxEventRepository.saveAndFlush(event);
    }

    /** Inserts a PENDING event with an explicit created_at, bypassing JPA auditing. */
    private void insertRawEvent(String eventType, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO outbox_events (event_type, aggregate_type, aggregate_id, payload, " +
                        "status, retry_count, created_at) VALUES (?, ?, ?, ?, 'PENDING', 0, ?)",
                eventType, "ORDER", 1L, "{\"test\":true}", createdAt);
    }
}
