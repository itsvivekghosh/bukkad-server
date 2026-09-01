package com.bhukkad.outbox;

import com.bhukkad.config.OutboxProperties;
import com.bhukkad.event.ExternalEventBridge;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderItemsSnapshotEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.bhukkad.event.PaymentWebhookReceivedEvent;
import com.bhukkad.logging.TracingBridge;
import com.bhukkad.logging.alert.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ExternalEventBridge externalEventBridge;
    private final DeadLetterEventService deadLetterEventService;
    private final OutboxProperties outboxProperties;
    private final AlertService alertService;
    private final PlatformTransactionManager transactionManager;

    /**
     * Sweeps the outbox. The claim uses {@code FOR UPDATE SKIP LOCKED} (see
     * {@link OutboxEventRepository#findPendingForProcessing}) so exactly one
     * replica processes each event even though every replica runs this poller;
     * the ShedLock annotation below is defense-in-depth so the sweep itself is
     * single-runner when ShedLock is enabled.
     *
     * <p>The sweep is deliberately <em>not</em> one long transaction: claiming
     * is its own short transaction (which marks the rows {@code PROCESSING} and
     * releases the SKIP LOCKED row locks), publishing/fan-out happens outside
     * any transaction, and each event's outcome (PUBLISHED / FAILED / back to
     * PENDING) commits in its own short transaction. A slow downstream (Kafka
     * broker, slow listener) can therefore never hold a DB connection or a row
     * lock across the whole batch.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @SchedulerLock(name = "outbox-processing", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    public void processPendingEvents() {
        for (OutboxEvent event : claimPendingBatch()) {
            processAndFinalize(event);
        }
    }

    /**
     * Resets events stranded in {@code PROCESSING} by a crashed or killed sweep
     * back to {@code PENDING} so they are retried. Guarded by ShedLock so the
     * recovery runs on a single replica.
     */
    @Scheduled(fixedDelayString = "${app.outbox.recovery-interval-ms:60000}")
    @SchedulerLock(name = "outbox-recovery", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void recoverStaleProcessing() {
        List<OutboxEvent> stale = newTransaction().execute(status ->
                outboxEventRepository.findStaleProcessing(
                        OutboxEvent.OutboxStatus.PROCESSING,
                        LocalDateTime.now().minusNanos(outboxProperties.getStaleProcessingAfterMs() * 1_000_000)));
        if (stale == null || stale.isEmpty()) {
            return;
        }
        for (OutboxEvent event : stale) {
            try {
                newTransaction().executeWithoutResult(exec -> {
                    OutboxEvent current = outboxEventRepository.findById(event.getId()).orElse(null);
                    if (current == null || current.getStatus() != OutboxEvent.OutboxStatus.PROCESSING) {
                        return;
                    }
                    current.setStatus(OutboxEvent.OutboxStatus.PENDING);
                    current.setProcessingStartedAt(null);
                    current.setLastError("processing abandoned (recovered from stale PROCESSING)");
                    outboxEventRepository.save(current);
                });
                log.warn("OUTBOX_RECOVERED | id={} | type={}", event.getId(), event.getEventType());
            } catch (Exception ex) {
                log.warn("OUTBOX_RECOVERY_FAILED | id={} | error={}", event.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Re-drives dead-lettered events back into the outbox. Runs on a
     * longer interval than the main sweep so transient downstream failures
     * (Kafka brokers down, etc.) get a chance to recover in between.
     */
    @Scheduled(fixedDelayString = "${app.outbox.dead-letter-repoll-ms:60000}")
    @SchedulerLock(name = "outbox-dead-letter-requeue", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void requeueDeadLetters() {
        // Jitter 0-5s to avoid thundering herd when DLQ > batch size and many pods schedule at same ms
        try {
            long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 5000);
            Thread.sleep(jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        deadLetterEventService.requeuePending(outboxProperties.getDeadLetterBatchSize());
    }

    // ==================== internals ====================

    /**
     * Claims a batch in its own short transaction: {@code FOR UPDATE SKIP
     * LOCKED} selects only rows no other replica has locked, marks them
     * {@code PROCESSING}, and commits — releasing the row locks while the row
     * state alone now guarantees single processing.
     */
    private List<OutboxEvent> claimPendingBatch() {
        return newTransaction().execute(status -> {
            List<OutboxEvent> pending = outboxEventRepository.findPendingForProcessing(
                    OutboxEvent.OutboxStatus.PENDING.name(),
                    outboxProperties.getBatchSize());
            if (pending.isEmpty()) {
                return pending;
            }
            LocalDateTime now = LocalDateTime.now();
            pending.forEach(event -> {
                event.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
                event.setProcessingStartedAt(now);
            });
            return outboxEventRepository.saveAll(pending);
        });
    }

    private void processAndFinalize(OutboxEvent event) {
        try (AutoCloseable span = TracingBridge.startSpan("outbox-" + event.getEventType())) {
            publish(event);
            externalEventBridge.forwardForResult(event);
            finalizeSuccess(event);
        } catch (Exception ex) {
            finalizeFailure(event, ex);
        }
    }

    private void finalizeSuccess(OutboxEvent event) {
        newTransaction().executeWithoutResult(status -> {
            outboxEventRepository.findById(event.getId()).ifPresent(current -> {
                current.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
                current.setPublishedAt(LocalDateTime.now());
                current.setProcessingStartedAt(null);
                current.setLastError(null);
                outboxEventRepository.save(current);
            });
        });
    }

    private void finalizeFailure(OutboxEvent event, Exception ex) {
        boolean deadLetter = newTransaction().execute(status -> {
            OutboxEvent current = outboxEventRepository.findById(event.getId()).orElse(null);
            if (current == null) {
                return false;
            }
            current.setRetryCount(current.getRetryCount() + 1);
            current.setLastError(ex.getMessage());
            if (current.getRetryCount() >= outboxProperties.getMaxRetries()) {
                current.setStatus(OutboxEvent.OutboxStatus.FAILED);
                current.setProcessingStartedAt(null);
                outboxEventRepository.save(current);
                return true;
            }
            // Back to the queue for the next sweep to retry (bounded by retryCount).
            current.setStatus(OutboxEvent.OutboxStatus.PENDING);
            current.setProcessingStartedAt(null);
            outboxEventRepository.save(current);
            return false;
        });

        if (deadLetter) {
            deadLetterEventService.record(event, ex.getMessage());
            long dlqSize = deadLetterEventService.countPending();
            log.error("OUTBOX_FAILED | id={} | type={} | dlqSize={} | error={}",
                    event.getId(), event.getEventType(), dlqSize, ex.getMessage());
            alertService.alertException("OutboxEventProcessor",
                    "Outbox event dead-lettered | id=" + event.getId()
                            + " | type=" + event.getEventType()
                            + " | dlqSize=" + dlqSize
                            + " | error=" + ex.getMessage(), ex);
        } else {
            log.warn("OUTBOX_RETRY | id={} | type={} | attempt={} | error={}",
                    event.getId(), event.getEventType(),
                    event.getRetryCount() + 1, ex.getMessage());
        }
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void publish(OutboxEvent event) throws Exception {
        // publishEvent is invoked with an explicit (Object) cast: without it,
        // the compiler resolves to Spring 6.1's generic publishEvent(T) default
        // overload, which Mockito mocks differently from publishEvent(Object) —
        // causing "Wanted but not invoked" verify failures on the unit test.
        // The explicit cast is a no-op for a real ApplicationEventPublisher
        // (the generic default delegates to publishEvent(Object) anyway).
        switch (event.getEventType()) {
            case "ORDER_CREATED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderCreatedEvent.class));
            case "ORDER_STATUS_CHANGED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderStatusChangedEvent.class));
            case "ORDER_AGENT_ASSIGNED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderAgentAssignedEvent.class));
            case "ORDER_ITEMS_SNAPSHOT" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderItemsSnapshotEvent.class));
            case "ORDER_SETTLED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), com.bhukkad.event.OrderSettledEvent.class));
            // Webhook receipts carry no side effects (the money path is applied
            // transactionally in the webhook controller). Route them to the
            // observability listener instead of dead-lettering every webhook.
            case "PAYMENT_WEBHOOK_RECEIVED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), PaymentWebhookReceivedEvent.class));
            default -> throw new IllegalArgumentException("Unknown outbox event type: " + event.getEventType());
        }
    }
}
