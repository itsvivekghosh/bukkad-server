package com.bhukkad.common.outbox;

import com.bhukkad.common.event.PlatformEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * Write-side outbox client. A service calls {@link #enqueue} inside its own
 * business transaction so the outbox row commits atomically with the domain
 * change; the poller publishes it to Kafka afterwards (plan §6.1).
 *
 * <p><strong>G-1 guard (audit §8, enforced by PERF-2/D6):</strong> enqueue
 * requires an ambient transaction. {@code MANDATORY} propagation makes a
 * call without one fail at the proxy boundary, and the in-body
 * {@link TransactionSynchronizationManager} check guards non-proxied usage
 * with the audit's named {@code G-1 violation} error. An event inserted in
 * its own committed transaction while the business transaction rolls back is
 * a phantom event — exactly the lost/duplicate-event class G-1 closes, so a
 * violation must roll the business transaction back, never be swallowed.</p>
 *
 * <p><strong>P-06 wake channel:</strong> when an {@link OutboxWakePublisher}
 * bean is present ({@code app.outbox.wake.enabled=true} + Redis on the
 * classpath), enqueue registers an {@code afterCommit} synchronization that
 * pings {@code bhukkad:outbox:wake:<service>} so the relay drains
 * immediately instead of waiting up to {@code pollInterval}. With the wake
 * enabled the E2E p99 &lt;2s event-delivery gate becomes reachable — the
 * default (disabled) keeps the behaviour identical to the poll-only relay,
 * and a lost wake is harmless because the periodic poll remains the
 * correctness backstop.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxClient {

    public static final String AGGREGATE_DEFAULT = "ORDER";

    private final OutboxEventRepository outboxEventRepository;

    /**
     * Optional-bean pattern: services construct {@code OutboxClient} via
     * their {@code @Bean} methods with the single repository argument, so the
     * wake publisher cannot be a constructor parameter without rippling into
     * every service's {@code PlatformConfig} (out of this batch's scope).
     * Spring injects this field on the {@code @Bean}-created instance when an
     * {@link OutboxWakePublisher} bean exists (wake enabled + Redis
     * configured); when absent it stays {@code null} and no wake is ever
     * registered — behaviour identical to the poll-only relay.
     */
    @Autowired(required = false)
    OutboxWakePublisher wakePublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, Long aggregateId, String payload) {
        enqueue(eventType, AGGREGATE_DEFAULT, aggregateId, payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, String aggregateType, Long aggregateId, String payload) {
        requireActiveTransaction();
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(payload);
        event.setStatus(OutboxEvent.OutboxStatus.PENDING);
        outboxEventRepository.save(event);
        registerWakeAfterCommit(eventType);
        log.debug("OUTBOX_ENQUEUED | type={} | aggregateType={} | aggregateId={}",
                eventType, aggregateType, aggregateId);
    }

    /**
     * Registers the P-06 wake ping to fire <em>after</em> the business
     * transaction commits — the outbox row must be durable before the relay
     * is woken, otherwise the drain races the insert. Skipped when no wake
     * publisher is wired or the transaction has no registered
     * synchronizations (e.g. plain unit-test activation).
     */
    private void registerWakeAfterCommit(String eventType) {
        if (wakePublisher == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wakePublisher.publishAfterCommit(eventType);
            }
        });
    }

    /**
     * Enqueues a full {@link PlatformEventMessage} envelope. The envelope's
     * {@code payload} is stored as the outbox payload; the envelope metadata
     * (eventId, correlationId, traceparent, occurredAt) is embedded as JSON so
     * the poller can reconstruct the Kafka message exactly.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PlatformEventMessage message, Long aggregateId) {
        enqueue(message.eventType(), aggregateTypeFrom(message), aggregateId, message.toJson());
    }

    private String aggregateTypeFrom(PlatformEventMessage message) {
        return message.aggregateId() == null || message.aggregateId().isBlank()
                ? AGGREGATE_DEFAULT : message.aggregateId();
    }

    /** Marker for the instant an event occurred, useful for audits and lag metrics. */
    public Instant now() {
        return Instant.now();
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "G-1 violation: outbox enqueue outside a business transaction — "
                            + "the event could outlive a rolled-back domain change");
        }
    }
}
