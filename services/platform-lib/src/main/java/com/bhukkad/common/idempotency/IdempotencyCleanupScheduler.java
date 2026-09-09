package com.bhukkad.common.idempotency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Centralised {@code idempotency_records} expiry sweep (audit V-19, PERF-2).
 *
 * <p>Previously only the identity service purged expired rows, so every other
 * service's dedupe table grew without bound (the V-11 webhook and V-12
 * projection rows this batch adds are produced by payment/admin-analytics).
 * The scheduler now ships in platform-lib, is component-scanned into every
 * service, and is enabled by default — a service opts out with
 * {@code app.idempotency.cleanup.enabled=false}.</p>
 *
 * <p>{@code @SchedulerLock} keeps replicas serialised where a ShedLock
 * {@code LockProvider} is configured (the repo-wide pattern from order's
 * {@code ScheduledOrderProcessor}); without a provider the deletes are
 * idempotent anyway. Batched 5 000-row sweeps keep each transaction short and
 * let a legacy backlog drain without vacuum pressure (V-19 rollout note).</p>
 *
 * <p>Metrics: {@code idempotency_cleaned{service=...}} counts purged rows.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.idempotency.cleanup.enabled", matchIfMissing = true)
public class IdempotencyCleanupScheduler {

    static final int BATCH_SIZE = 5000;

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionTemplate transactionTemplate;
    private final Counter cleanedCounter;

    public IdempotencyCleanupScheduler(IdempotencyRecordRepository idempotencyRecordRepository,
                                       PlatformTransactionManager transactionManager,
                                       MeterRegistry meterRegistry,
                                       @Value("${spring.application.name:unknown}") String serviceName) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.cleanedCounter = Counter.builder("idempotency_cleaned")
                .description("Expired idempotency records purged (V-19)")
                .tag("service", serviceName)
                .register(meterRegistry);
    }

    /** Hourly sweep; one caller at a time via ShedLock when a provider exists. */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "idempotency-cleanup", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void purgeExpiredRecords() {
        int total = 0;
        int removed;
        // One transaction per batch keeps locks short while a big legacy
        // backlog drains across successive batches. (TransactionTemplate —
        // self-invoked @Transactional would be proxy-bypassed, the exact B1
        // class of bug this batch is fixing.)
        do {
            removed = transactionTemplate.execute(status ->
                    idempotencyRecordRepository.deleteBatch(LocalDateTime.now(), BATCH_SIZE));
            total += removed;
        } while (removed >= BATCH_SIZE);
        if (total > 0) {
            cleanedCounter.increment(total);
            log.info("IDEMPOTENCY_CLEANED | removed={}", total);
        }
    }
}
