package com.bhukkad.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Scheduled outbox retention sweep (audit P2-06).
 *
 * <p>Hard-deletes terminal {@code outbox_events} rows (PUBLISHED / FAILED)
 * older than {@code app.events.external.kafka.outbox.retention-ttl} (default
 * 7 days). Batches are capped at {@code app.events.external.kafka.outbox.batchSize}
 * so a single sweep never holds a long write lock or blocks the relay's
 * claim/publish loop. Runs with a fixed delay so consecutive sweeps never
 * overlap, and deletes in a loop until a batch returns zero rows.</p>
 *
 * <p>The service is a no-op when the outbox relay is disabled (bean gate on
 * {@link OutboxProperties#isEnabled()} via the platform's single
 * {@code app.events.external.*} gate) — single-DB deployments without Kafka
 * never run retention.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRetentionService {

    private static final int BATCH_LIMIT = 1000;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties outboxProperties;

    /**
     * Runs every 5 minutes so retention never starves the relay's claim cycles.
     * Each invocation drains terminal rows in batches until none remain older
     * than the TTL.
     */
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void sweepOldTerminalEvents() {
        if (outboxProperties == null || outboxProperties.retentionTtl() == null) {
            return;
        }
        LocalDateTime olderThan = LocalDateTime.now().minus(outboxProperties.retentionTtl());
        int totalDeleted = 0;
        int batch;
        do {
            batch = outboxEventRepository.deleteOldTerminal(olderThan, BATCH_LIMIT);
            totalDeleted += batch;
        } while (batch > 0);
        if (totalDeleted > 0) {
            log.info("OUTBOX_RETENTION_SWEEP deletedRows={} olderThan={}",
                    totalDeleted, olderThan);
        }
    }
}
