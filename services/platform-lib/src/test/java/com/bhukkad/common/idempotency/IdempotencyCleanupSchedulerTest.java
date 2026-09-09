package com.bhukkad.common.idempotency;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V-19: batched hourly sweep, counter, and correct annotations. */
class IdempotencyCleanupSchedulerTest {

    @Test
    void purge_loopsBatchesUntilUnderFullPage_andCounts() {
        IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
        when(repository.deleteBatch(any(), eq(5000))).thenReturn(5000, 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IdempotencyCleanupScheduler scheduler =
                new IdempotencyCleanupScheduler(repository, new NoOpTxManager(), registry, "test-svc");

        scheduler.purgeExpiredRecords();

        verify(repository, times(2)).deleteBatch(any(), eq(5000));
        assertThat(registry.get("idempotency_cleaned").tag("service", "test-svc")
                .counter().count()).isEqualTo(5003.0);
    }

    @Test
    void purge_noExpired_singleBatchZeroNoCounterTraffic() {
        IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
        when(repository.deleteBatch(any(), anyInt())).thenReturn(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IdempotencyCleanupScheduler scheduler =
                new IdempotencyCleanupScheduler(repository, new NoOpTxManager(), registry, "test-svc");

        scheduler.purgeExpiredRecords();

        verify(repository, times(1)).deleteBatch(any(), eq(5000));
        // Counter stays registered but at zero — no false purge signal.
        assertThat(registry.get("idempotency_cleaned").tag("service", "test-svc")
                .counter().count()).isZero();
    }

    @Test
    void annotations_scheduleHourlyAndLockAgainstReplicas() throws Exception {
        var method = IdempotencyCleanupScheduler.class.getMethod("purgeExpiredRecords");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 * * * *");
        // ShedLock honoured only where a LockProvider is configured; the
        // annotation must at least be declared per the audit requirement.
        var lock = method.getAnnotation(
                net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("idempotency-cleanup");
    }

    @Test
    void gating_defaultsToOn() {
        var ann = IdempotencyCleanupScheduler.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).containsExactly("app.idempotency.cleanup.enabled");
        assertThat(ann.matchIfMissing()).isTrue();
    }

    /** Minimal tx manager so TransactionTemplate.execute runs the callback. */
    private static final class NoOpTxManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction,
                               org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
