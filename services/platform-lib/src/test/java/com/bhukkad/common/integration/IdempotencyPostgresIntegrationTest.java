package com.bhukkad.common.integration;

import com.bhukkad.common.AbstractPostgresIntegrationTest;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the DB-backed idempotency guard on PostgreSQL: first-write-wins
 * unique constraint, concurrent race handling, and TTL purge.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = com.bhukkad.common.PlatformTestConfig.class)
class IdempotencyPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void firstInsertWins() {
        IdempotencyRecord record = createRecord("key-1",
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE,
                IdempotencyRecord.IdempotencyStatus.COMPLETED);

        assertThat(record.getId()).isNotNull();
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-1")).isPresent();
    }

    @Test
    void duplicateKey_throwsConstraintViolation() {
        createRecord("key-dup", IdempotencyRecord.IdempotencyScope.ORDER_CREATE,
                IdempotencyRecord.IdempotencyStatus.COMPLETED);

        assertThatThrownBy(() -> createRecord("key-dup", IdempotencyRecord.IdempotencyScope.ORDER_CREATE,
                IdempotencyRecord.IdempotencyStatus.IN_PROGRESS))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyDifferentScope_doesNotConflict() {
        createRecord("key-1", IdempotencyRecord.IdempotencyScope.ORDER_CREATE,
                IdempotencyRecord.IdempotencyStatus.COMPLETED);
        createRecord("key-1", IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS,
                IdempotencyRecord.IdempotencyStatus.COMPLETED);

        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-1")).isPresent();
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-1")).isPresent();
    }

    @Test
    void deleteByExpiresAtBefore_removesOnlyExpired() {
        createRecord("expired", IdempotencyRecord.IdempotencyScope.ORDER_CREATE,
                IdempotencyRecord.IdempotencyStatus.COMPLETED, LocalDateTime.now().minusDays(1));
        createRecord("valid", IdempotencyRecord.IdempotencyScope.ORDER_CREATE,
                IdempotencyRecord.IdempotencyStatus.COMPLETED, LocalDateTime.now().plusDays(1));

        int deleted = repository.deleteByExpiresAtBefore(LocalDateTime.now());

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "expired")).isEmpty();
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "valid")).isPresent();
    }

    @Test
    void deleteBatch_boundedSweep_removesOldestExpiredFirst_livesUntouched() {
        // V-19: batched cleanup — a 5000-page delete must be bounded, ordered
        // and never touch unexpired rows.
        insertRecord("k-batch-1", IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME,
                java.time.LocalDateTime.now().minusDays(2));
        insertRecord("k-batch-2", IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME,
                java.time.LocalDateTime.now().minusDays(2));
        insertRecord("k-batch-3", IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME,
                java.time.LocalDateTime.now().minusSeconds(1));
        insertRecord("k-live", IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME,
                java.time.LocalDateTime.now().plusDays(1));

        int removed = repository.deleteBatch(java.time.LocalDateTime.now(), 2);
        assertThat(removed).isEqualTo(2);
        int removed2 = repository.deleteBatch(java.time.LocalDateTime.now(), 2);
        assertThat(removed2).isEqualTo(1);
        assertThat(repository.deleteBatch(java.time.LocalDateTime.now(), 2)).isZero();
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME, "k-live")).isPresent();
    }

    @Test
    void deleteByScopeAndIdempotencyKey_targetedRelease() {
        insertRecord("k-targeted", IdempotencyRecord.IdempotencyScope.ADMIN_PROJECTION,
                java.time.LocalDateTime.now().plusDays(1));
        int deleted = repository.deleteByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ADMIN_PROJECTION, "k-targeted");
        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ADMIN_PROJECTION, "k-targeted")).isEmpty();
    }

    private void insertRecord(String key, IdempotencyRecord.IdempotencyScope scope,
                              java.time.LocalDateTime expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setScope(scope);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setExpiresAt(expiresAt);
        repository.saveAndFlush(record);
    }

    @Test
    void insertIfAbsent_firstWins_secondIsNoop() {
        int first = repository.insertIfAbsent("key-ia", IdempotencyRecord.IdempotencyScope.ORDER_CREATE.name(),
                1L, IdempotencyRecord.IdempotencyStatus.IN_PROGRESS.name(), "{}", LocalDateTime.now().plusHours(1));
        int second = repository.insertIfAbsent("key-ia", IdempotencyRecord.IdempotencyScope.ORDER_CREATE.name(),
                1L, IdempotencyRecord.IdempotencyStatus.IN_PROGRESS.name(), "{}", LocalDateTime.now().plusHours(1));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-ia")).isPresent();
    }

    @Test
    void concurrentInsert_onlyOneWins() throws Exception {
        int threads = 10;
        String sharedKey = "concurrent-race-key";
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        tx.execute(status -> {
                            IdempotencyRecord r = new IdempotencyRecord();
                            r.setIdempotencyKey(sharedKey);
                            r.setScope(IdempotencyRecord.IdempotencyScope.ORDER_CREATE);
                            r.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
                            r.setExpiresAt(LocalDateTime.now().plusHours(1));
                            repository.save(r);
                            repository.flush();
                            successCount.incrementAndGet();
                            return null;
                        });
                    } catch (Exception e) {
                        conflictCount.incrementAndGet();
                    }
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(threads - 1);
            assertThat(repository.findByScopeAndIdempotencyKey(
                    IdempotencyRecord.IdempotencyScope.ORDER_CREATE, sharedKey)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    private IdempotencyRecord createRecord(String key, IdempotencyRecord.IdempotencyScope scope,
                                           IdempotencyRecord.IdempotencyStatus status) {
        return createRecord(key, scope, status, LocalDateTime.now().plusDays(1));
    }

    private IdempotencyRecord createRecord(String key, IdempotencyRecord.IdempotencyScope scope,
                                           IdempotencyRecord.IdempotencyStatus status, LocalDateTime expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setScope(scope);
        record.setStatus(status);
        record.setExpiresAt(expiresAt);
        return repository.saveAndFlush(record);
    }
}
