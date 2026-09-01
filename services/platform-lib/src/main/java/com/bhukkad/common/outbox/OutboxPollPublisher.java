package com.bhukkad.common.outbox;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Per-service outbox relay (plan §6.1): claims {@code PENDING} rows, publishes
 * the embedded {@link PlatformEventMessage} envelope to Kafka via
 * {@link KafkaPlatformEventPublisher#publishForResult}, and flips each row to
 * {@code PUBLISHED} on ack or {@code PROCESSING} (re-queueable by
 * {@link #recoverStale()}) on failure.
 *
 * <p>Claiming uses the repository's {@code FOR UPDATE SKIP LOCKED} query, so
 * horizontally scaled poller replicas each grab disjoint batches without
 * blocking. The failed-step bookkeeping is the responsibility of the saga
 * coordinator; the outbox only guarantees <em>event delivery</em>.</p>
 *
 * <p>The {@link #drainBatch()} method is the unit of work and is testable
 * without Spring; the {@link Scheduled} wrapper is gated off here and is only
 * meaningful inside a {@code @EnableScheduling} service context. Gating at the
 * class level would also drop the bean, so runtime enablement is delegated to
 * {@link OutboxProperties#isEnabled()} and a conditional caller.</p>
 */
@Slf4j
public class OutboxPollPublisher {

    private final OutboxEventRepository repository;
    private final KafkaPlatformEventPublisher publisher;
    private final OutboxProperties properties;

    public OutboxPollPublisher(OutboxEventRepository repository,
                               KafkaPlatformEventPublisher publisher,
                               OutboxProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * Claims up to {@code batchSize} PENDING rows and publishes them.
     *
     * @return the number of rows flipped to {@code PUBLISHED}.
     */
    @Transactional
    public int drainBatch() {
        if (!properties.isEnabled()) {
            return 0;
        }
        List<OutboxEvent> claimed = claimBatch(properties.batchSize());
        if (claimed.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int published = 0;
        for (OutboxEvent event : claimed) {
            try {
                PlatformEventMessage message = PlatformEventMessage.fromJson(event.getPayload());
                if (publisher.publishForResult(message)) {
                    event.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
                    event.setPublishedAt(now);
                    event.setLastError(null);
                    published++;
                    log.debug("OUTBOX_PUBLISHED | id={} | eventId={} | type={}",
                            event.getId(), message.eventId(), message.eventType());
                } else {
                    // Broker unreachable within the timeout — keep re-queueable.
                    bumpRetry(event, now, "publish timed out");
                }
            } catch (IllegalArgumentException e) {
                // Malformed payload (e.g. corrupted row): move to FAILED, do not retry.
                event.setStatus(OutboxEvent.OutboxStatus.FAILED);
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(truncate(e.getMessage(), properties.maxErrorLength()));
                log.error("OUTBOX_DEAD_LETTER | id={} | reason=invalid_payload | error={}",
                        event.getId(), e.getMessage());
            } catch (Exception e) {
                bumpRetry(event, now, e.getMessage());
            } finally {
                repository.save(event);
            }
        }
        return published;
    }

    /**
     * Re-queues rows stuck in {@code PROCESSING} longer than the processing
     * timeout, so a crashed poller does not leave events stranded. Safe to run
     * concurrently with {@link #drainBatch()}: a row in PROCESSING is not
     * selected by the CLAIM query.
     */
    @Transactional
    public int recoverStale() {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime staleBefore = LocalDateTime.ofInstant(
                Instant.now().minus(properties.processingTimeout()), ZoneId.systemDefault());
        List<OutboxEvent> stale = repository.findStaleProcessing(
                OutboxEvent.OutboxStatus.PROCESSING, staleBefore);
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (OutboxEvent event : stale) {
            event.setStatus(OutboxEvent.OutboxStatus.PENDING);
            event.setProcessingStartedAt(null);
            event.setLastError(truncate("recovered from stalled PROCESSING", properties.maxErrorLength()));
            repository.save(event);
            recovered++;
        }
        if (recovered > 0) {
            log.warn("OUTBOX_STALE_RECOVERED | count={}", recovered);
        }
        return recovered;
    }

    /** Total pending+processing rows — useful for lag/queue-depth metrics. */
    public long pendingCount() {
        return repository.countByStatus(OutboxEvent.OutboxStatus.PENDING)
                + repository.countByStatus(OutboxEvent.OutboxStatus.PROCESSING);
    }

    /**
     * Scheduled entry point. The actual claim/publish logic is
     * {@link #drainBatch()} so it can be unit-tested without a task scheduler.
     */
    @Scheduled(fixedDelayString = "#{@outboxProperties.pollInterval().toMillis()}")
    public void poll() {
        try {
            drainBatch();
        } catch (Exception e) {
            // A batch failure must not kill the scheduler; recovery keeps retrying.
            log.error("OUTBOX_POLL_FAILED | error={}", e.getMessage(), e);
        }
    }

    /** Claim (lock) PENDING rows and flip them to PROCESSING within this tx. */
    private List<OutboxEvent> claimBatch(int batchSize) {
        List<OutboxEvent> claimed = repository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), batchSize);
        if (!claimed.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (OutboxEvent event : claimed) {
                event.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
                event.setProcessingStartedAt(now);
            }
            repository.saveAll(claimed);
        }
        return claimed;
    }

    private void bumpRetry(OutboxEvent event, LocalDateTime now, String errorMessage) {
        event.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
        event.setRetryCount(event.getRetryCount() + 1);
        event.setProcessingStartedAt(now);
        event.setLastError(truncate(errorMessage, properties.maxErrorLength()));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        int bound = Math.min(value.length(), Math.max(0, max));
        return value.substring(0, bound);
    }
}
