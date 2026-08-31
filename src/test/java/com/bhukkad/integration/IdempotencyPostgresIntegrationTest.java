package com.bhukkad.integration;

import com.bhukkad.idempotency.IdempotencyRecord;
import com.bhukkad.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
 * Validates the DB-backed layer of the dual-layer idempotency guard against a
 * real PostgreSQL 16 database (architecture-microservices-postgresql.md §12 P0:
 * "port ... idempotency lib tests to PG").
 *
 * <p>The idempotency model relies on a UNIQUE constraint on
 * {@code (scope, idempotency_key)} as the authoritative first-write-wins guard.
 * The Redis fast-path (which is optional) fails open onto this constraint.
 * These tests prove the constraint works correctly on PostgreSQL, including
 * under concurrent insert races that simulate duplicate request bursts.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class IdempotencyPostgresIntegrationTest extends AbstractPostgresJpaIntegrationTest {

    private static final String KEY = "test-idem-key-001";
    private static final IdempotencyRecord.IdempotencyScope SCOPE =
            IdempotencyRecord.IdempotencyScope.ORDER_CREATE;

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
        IdempotencyRecord record = createRecord(KEY, SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED);

        assertThat(record.getId()).isNotNull();
        Optional<IdempotencyRecord> found = repository.findByScopeAndIdempotencyKey(SCOPE, KEY);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(IdempotencyRecord.IdempotencyStatus.COMPLETED);
    }

    @Test
    void duplicateKey_throwsDataIntegrityViolation() {
        createRecord(KEY, SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED);

        assertThatThrownBy(() -> createRecord(KEY, SCOPE, IdempotencyRecord.IdempotencyStatus.IN_PROGRESS))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameScopeDifferentKey_doesNotConflict() {
        createRecord("key-1", SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED);
        createRecord("key-2", SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED);

        assertThat(repository.findByScopeAndIdempotencyKey(SCOPE, "key-1")).isPresent();
        assertThat(repository.findByScopeAndIdempotencyKey(SCOPE, "key-2")).isPresent();
    }

    @Test
    void sameKeyDifferentScope_doesNotConflict() {
        createRecord(KEY, IdempotencyRecord.IdempotencyScope.ORDER_CREATE,
                IdempotencyRecord.IdempotencyStatus.COMPLETED);
        createRecord(KEY, IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS,
                IdempotencyRecord.IdempotencyStatus.COMPLETED);

        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, KEY)).isPresent();
        assertThat(repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, KEY)).isPresent();
    }

    @Test
    void completedRecord_returnsCachedResponse() {
        IdempotencyRecord record = createRecord(KEY, SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{\"orderId\":42}");
        repository.save(record);

        Optional<IdempotencyRecord> found = repository.findByScopeAndIdempotencyKey(SCOPE, KEY);
        assertThat(found).isPresent();
        assertThat(found.get().getResponsePayload()).isEqualTo("{\"orderId\":42}");
    }

    @Test
    void inProgressRecord_doesNotHaveResponse() {
        createRecord(KEY, SCOPE, IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        Optional<IdempotencyRecord> found = repository.findByScopeAndIdempotencyKey(SCOPE, KEY);
        assertThat(found).isPresent();
        assertThat(found.get().getResponsePayload()).isNull();
    }

    @Test
    void ownerId_canBeNull() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey("no-owner-key");
        record.setScope(IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setExpiresAt(LocalDateTime.now().plusDays(1));
        repository.saveAndFlush(record);

        Optional<IdempotencyRecord> found = repository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, "no-owner-key");
        assertThat(found).isPresent();
        assertThat(found.get().getOwnerId()).isNull();
    }

    @Test
    void deleteByExpiresAtBefore_removesOnlyExpired() {
        createRecord("expired", SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED,
                LocalDateTime.now().minusDays(1));
        createRecord("valid", SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED,
                LocalDateTime.now().plusDays(1));

        int deleted = repository.deleteByExpiresAtBefore(LocalDateTime.now());

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByScopeAndIdempotencyKey(SCOPE, "expired")).isEmpty();
        assertThat(repository.findByScopeAndIdempotencyKey(SCOPE, "valid")).isPresent();
    }

    @Test
    void deleteByExpiresAtBefore_zeroWhenNoneExpired() {
        createRecord("fresh", SCOPE, IdempotencyRecord.IdempotencyStatus.COMPLETED,
                LocalDateTime.now().plusHours(1));

        int deleted = repository.deleteByExpiresAtBefore(LocalDateTime.now());

        assertThat(deleted).isZero();
    }

    /**
     * Heavy-traffic guard: N concurrent threads all try to insert the same
     * (scope, key) pair. Exactly one must succeed; the rest must fail with a
     * unique-constraint violation. This simulates multiple application instances
     * processing the same duplicate request simultaneously.
     */
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
                    // The catch must sit OUTSIDE tx.execute: catching inside the
                    // callback leaves the transaction marked rollback-only and
                    // the template throws UnexpectedRollbackException on commit.
                    try {
                        tx.execute(status -> {
                            IdempotencyRecord r = new IdempotencyRecord();
                            r.setIdempotencyKey(sharedKey);
                            r.setScope(SCOPE);
                            r.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
                            r.setExpiresAt(LocalDateTime.now().plusHours(1));
                            repository.save(r);
                            repository.flush();
                            return null;
                        });
                        successCount.incrementAndGet();
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
            assertThat(repository.findByScopeAndIdempotencyKey(SCOPE, sharedKey)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    private IdempotencyRecord createRecord(String key, IdempotencyRecord.IdempotencyScope scope,
                                           IdempotencyRecord.IdempotencyStatus status) {
        return createRecord(key, scope, status, LocalDateTime.now().plusDays(1));
    }

    private IdempotencyRecord createRecord(String key, IdempotencyRecord.IdempotencyScope scope,
                                           IdempotencyRecord.IdempotencyStatus status,
                                           LocalDateTime expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setScope(scope);
        record.setStatus(status);
        record.setExpiresAt(expiresAt);
        if (status == IdempotencyRecord.IdempotencyStatus.COMPLETED) {
            record.setOwnerId(42L);
        }
        return repository.saveAndFlush(record);
    }
}