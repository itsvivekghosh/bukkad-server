package com.bhukkad.common.outbox;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-service outbox relay (plan §6.1, hardened by PERF-2/B1): claims
 * {@code PENDING} rows, publishes the embedded {@link PlatformEventMessage}
 * envelope to Kafka via
 * {@link KafkaPlatformEventPublisher#publishForResult}, and flips each batch
 * to {@code PUBLISHED} on ack or re-queues it with exponential backoff
 * ({@code next_attempt_at}) on failure.
 *
 * <p><strong>Two-phase transaction discipline.</strong> The old relay relied on
 * a self-invoked {@code @Transactional} {@code drainBatch()} — the Spring proxy
 * was bypassed, so the {@code FOR UPDATE SKIP LOCKED} claim never ran inside a
 * real transaction and two replicas could claim the same rows (duplicate
 * publishes; audit finding B1 §2.4). The relay now performs:</p>
 * <ol>
 *   <li><strong>claim tx</strong> — {@link TransactionTemplate}:
 *       {@code SELECT … FOR UPDATE SKIP LOCKED} + batched
 *       {@code UPDATE … SET status='PROCESSING' WHERE id IN (:ids)}, commit
 *       (row locks drop only after the flip is durable);</li>
 *   <li><strong>publish</strong> — outside any tx/broker ack on the dedicated
 *       {@code relay-} scheduler thread, never holding DB connections;</li>
 *   <li><strong>state tx</strong> — one short tx with batched
 *       {@code UPDATE … WHERE id IN (:ids)} per outcome group
 *       (guide §6 PERF-3.5).</li>
 * </ol>
 *
 * <p><strong>Retry budget:</strong> a failed publish bumps
 * {@code retry_count} and defers {@code next_attempt_at} exponentially (capped
 * at the processing timeout). Once {@code retryCount >= maxRetries} the row is
 * copied to {@code dead_letter_events} via {@link DeadLetterEventService}
 * (counting {@code outbox_dlq_total}) and marked {@code FAILED}. Malformed
 * payloads dead-letter immediately (retrying cannot fix a corrupt row).</p>
 *
 * <p><strong>Scheduling:</strong> this class no longer carries
 * {@code @Scheduled} — {@link OutboxRelayBootstrap} registers the poll and the
 * {@code recoverStale} sweep on a dedicated two-thread {@code relay-}
 * {@link org.springframework.scheduling.TaskScheduler}
 * ({@link OutboxRelayBootstrap}), so the relay neither shares the
 * service's default scheduler thread (B1: 14 jobs, one thread) nor depends on
 * {@code spring.task.scheduling.pool.size}.</p>
 *
 * <p>Gating: the relay bean is created only under the same
 * {@code enabled=true + type=kafka} expression as the Kafka publisher
 * (PERF-2/B2 — one {@code ExternalEventsProperties} gate; when the publisher
 * is off, {@link KafkaPlatformEventPublisher#publishForResult} returns
 * {@code false} and rows are never silently marked PUBLISHED).</p>
 */
@Slf4j
public class OutboxPollPublisher {

    private final OutboxEventRepository repository;
    private final KafkaPlatformEventPublisher publisher;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final DeadLetterEventService deadLetterEvents;
    private final io.micrometer.core.instrument.Counter dlqCounter;
    private final DistributionSummary publishLag;

    public OutboxPollPublisher(OutboxEventRepository repository,
                               KafkaPlatformEventPublisher publisher,
                               OutboxProperties properties,
                               TransactionTemplate transactionTemplate,
                               DeadLetterEventService deadLetterEvents,
                               MeterRegistry meterRegistry) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.deadLetterEvents = deadLetterEvents;
        // Prometheus surfaces this as outbox_dlq_total (PERF-2 alert metric).
        this.dlqCounter = meterRegistry.counter("outbox.dlq");
        // P-06: ms histogram of now - createdAt at successful publish; with the
        // wake channel enabled this is the E2E p99 <2s budget evidence.
        this.publishLag = DistributionSummary.builder(OutboxMetrics.PUBLISH_LAG_METRIC_NAME)
                .baseUnit("ms")
                .description("Outbox publish lag: ms between row createdAt and successful Kafka publish")
                .register(meterRegistry);
    }

    /**
     * Drains one batch: claim tx → publish (no tx) → state tx.
     *
     * @return the number of rows flipped to {@code PUBLISHED}.
     */
    public int drainBatch() {
        List<OutboxEvent> claimed = claimBatch();
        if (claimed.isEmpty()) {
            return 0;
        }
        Outcome outcome = publishOutsideTx(claimed);
        return persistOutcome(outcome);
    }

    // ── Phase 1: claim (real transaction) ────────────────────────────────────

    /**
     * Locks and flips one batch PENDING → PROCESSING inside a dedicated
     * transaction; the commit releases the {@code SKIP LOCKED} row locks, so a
     * second relay replica never observes the same rows as PENDING again.
     * Entities are returned detached (used for routing decisions only).
     */
    private List<OutboxEvent> claimBatch() {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> pending = repository.findPendingForProcessing(
                    OutboxEvent.OutboxStatus.PENDING.name(),
                    properties.batchSize(),
                    LocalDateTime.now());
            if (pending.isEmpty()) {
                return List.<OutboxEvent>of();
            }
            List<Long> ids = pending.stream().map(OutboxEvent::getId).toList();
            LocalDateTime now = LocalDateTime.now();
            int flipped = repository.markProcessing(ids, now);
            if (flipped != ids.size()) {
                // Another replica claimed rows between SELECT and UPDATE cannot
                // happen under SKIP LOCKED; a mismatch means the batch shrank.
                log.warn("OUTBOX_CLAIM_PARTIAL | selected={} | flipped={}", ids.size(), flipped);
            }
            // Stamp the PROCESSING flip locally so the publish phase sees rows
            // in the state the DB now holds (the entities are detached after
            // this tx commits; only id/payload/retryCount are read).
            pending.forEach(e -> e.setStatus(OutboxEvent.OutboxStatus.PROCESSING));
            return pending;
        });
    }

    // ── Phase 2: publish (no transaction) ────────────────────────────────────

    /** Routes each claimed row into published / retry / dead-letter groups. */
    private Outcome publishOutsideTx(List<OutboxEvent> claimed) {
        Outcome outcome = new Outcome();
        for (OutboxEvent event : claimed) {
            PlatformEventMessage message;
            try {
                message = PlatformEventMessage.fromJson(event.getPayload());
            } catch (IllegalArgumentException e) {
                // Malformed payload (e.g. corrupted row): retrying cannot help —
                // copy to dead_letter_events and stop.
                outcome.dead(event, truncate(e.getMessage(), properties.maxErrorLength()));
                log.error("OUTBOX_DEAD_LETTER | id={} | reason=invalid_payload | error={}",
                        event.getId(), e.getMessage());
                continue;
            }
            boolean acked;
            String error = "publish timed out";
            try {
                acked = publisher.publishForResult(message);
            } catch (Exception e) {
                acked = false;
                error = e.getMessage();
            }
            if (acked) {
                outcome.published.add(event.getId());
                recordPublishLag(event);
                log.debug("OUTBOX_PUBLISHED | id={} | eventId={} | type={}",
                        event.getId(), message.eventId(), message.eventType());
                continue;
            }
            int attemptsAfterThisOne = event.getRetryCount() + 1;
            if (attemptsAfterThisOne >= properties.maxRetries()) {
                outcome.dead(event, truncate(error, properties.maxErrorLength()));
            } else {
                outcome.retry(event, truncate(error, properties.maxErrorLength()),
                        LocalDateTime.now().plus(properties.backoffFor(attemptsAfterThisOne)));
            }
        }
        return outcome;
    }

    /**
     * P-06: records {@code now - createdAt} in ms for a successfully published
     * row. Rows without an audit timestamp (unit-test fixtures) are skipped;
     * a negative value (clock skew) is never recorded.
     */
    private void recordPublishLag(OutboxEvent event) {
        if (event.getCreatedAt() == null) {
            return;
        }
        long lagMs = Duration.between(event.getCreatedAt(), LocalDateTime.now()).toMillis();
        if (lagMs >= 0) {
            publishLag.record(lagMs);
        }
    }

    // ── Phase 3: state flip (short transaction, batched updates) ─────────────

    private int persistOutcome(Outcome outcome) {
        Integer count = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            if (!outcome.published.isEmpty()) {
                repository.markPublished(outcome.published, now);
            }
            // Retries are grouped by (next_attempt_at, error) so each distinct
            // group still costs exactly ONE batched UPDATE — the common case is
            // a single group for the whole batch.
            for (Map.Entry<RetryKey, List<Long>> group : outcome.retries.entrySet()) {
                RetryKey key = group.getKey();
                repository.markPendingRetry(group.getValue(), key.nextAttemptAt, key.error);
            }
            for (Map.Entry<String, List<Long>> group : outcome.deadByError.entrySet()) {
                repository.markFailed(group.getValue(), group.getKey());
            }
            return outcome.published.size();
        });
        // Dead-letter copies happen AFTER the terminal flip commits: a DLQ write
        // failure must not roll back the FAILED state (the outbox row keeps the
        // payload either way — same guarantee as DeadLetterEventService.record).
        for (Map.Entry<String, List<OutboxEvent>> group : outcome.deadRowsByError.entrySet()) {
            for (OutboxEvent event : group.getValue()) {
                deadLetterEvents.record(event, group.getKey());
                dlqCounter.increment();
            }
        }
        return count == null ? 0 : count;
    }

    // ── Scheduled entry points (wired by OutboxRelayBootstrap) ───────────────

    /** One poll cycle. A batch failure must never kill the relay loop. */
    public void poll() {
        try {
            drainBatch();
        } catch (Exception e) {
            log.error("OUTBOX_POLL_FAILED | error={}", e.getMessage(), e);
        }
    }

    /**
     * Re-queues rows stuck in {@code PROCESSING} longer than the processing
     * timeout, so a crashed relay does not strand events. Scheduled
     * periodically by {@link OutboxRelayBootstrap} (PERF-2: previously only
     * reachable from tests, so every broker blip stranded rows forever).
     */
    public int recoverStale() {
        LocalDateTime staleBefore = LocalDateTime.ofInstant(
                Instant.now().minus(properties.processingTimeout()), ZoneId.systemDefault());
        Integer recovered = transactionTemplate.execute(status -> repository.recoverStaleToPending(
                staleBefore,
                truncate("recovered from stalled PROCESSING", properties.maxErrorLength())));
        int count = recovered == null ? 0 : recovered;
        if (count > 0) {
            log.warn("OUTBOX_STALE_RECOVERED | count={}", count);
        }
        return count;
    }

    /** Total pending+processing rows — useful for lag/queue-depth metrics. */
    public long pendingCount() {
        return repository.countByStatus(OutboxEvent.OutboxStatus.PENDING)
                + repository.countByStatus(OutboxEvent.OutboxStatus.PROCESSING);
    }

    /** Mutable routing accumulator for a batch; entity refs kept for dead-letter copies. */
    private static final class Outcome {
        final List<Long> published = new ArrayList<>();
        final Map<RetryKey, List<Long>> retries = new LinkedHashMap<>();
        final Map<String, List<Long>> deadByError = new LinkedHashMap<>();
        final Map<String, List<OutboxEvent>> deadRowsByError = new LinkedHashMap<>();

        void retry(OutboxEvent event, String error, LocalDateTime nextAttemptAt) {
            retries.computeIfAbsent(new RetryKey(nextAttemptAt, error), k -> new ArrayList<>())
                    .add(event.getId());
        }

        void dead(OutboxEvent event, String error) {
            deadByError.computeIfAbsent(error, k -> new ArrayList<>()).add(event.getId());
            deadRowsByError.computeIfAbsent(error, k -> new ArrayList<>()).add(event);
        }
    }

    private record RetryKey(LocalDateTime nextAttemptAt, String error) {
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "publish failed";
        }
        int bound = Math.min(value.length(), Math.max(0, max));
        return value.substring(0, bound);
    }
}
