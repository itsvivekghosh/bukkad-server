package com.bhukkad.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persists outbox events that exhausted their retry budget and supports
 * re-driving them back into the outbox for a later attempt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterEventService {

    public static final String SOURCE_OUTBOX = "OUTBOX";
    public static final String SOURCE_KAFKA = "KAFKA";

    private final DeadLetterEventRepository deadLetterEventRepository;
    private final OutboxEventRepository outboxEventRepository;

    /**
     * Copies an exhausted outbox event into the dead-letter table for audit.
     * The original outbox row is left untouched (status FAILED) so the
     * payload is never lost even if the DLQ write fails.
     */
    @Transactional
    public void record(OutboxEvent event, String error) {
        try {
            DeadLetterEvent deadLetter = new DeadLetterEvent();
            deadLetter.setEventType(event.getEventType());
            deadLetter.setAggregateType(event.getAggregateType());
            deadLetter.setAggregateId(event.getAggregateId());
            deadLetter.setPayload(event.getPayload());
            deadLetter.setLastError(error);
            deadLetter.setRetryCount(event.getRetryCount());
            deadLetter.setSource(SOURCE_OUTBOX);
            deadLetter.setStatus(DeadLetterEvent.DlqStatus.PENDING);
            deadLetterEventRepository.save(deadLetter);
            log.warn("OUTBOX_DEAD_LETTERED | id={} | type={} | aggregateId={} | error={}",
                    event.getId(), event.getEventType(), event.getAggregateId(), error);
        } catch (Exception ex) {
            log.error("OUTBOX_DEAD_LETTER_WRITE_FAILED | id={} | type={} | error={}",
                    event.getId(), event.getEventType(), ex.getMessage(), ex);
        }
    }

    /**
     * Requeues dead-lettered events back into the outbox as PENDING so the
     * polling processor can retry them. Marks the DLQ row REQUEUED.
     */
    @Transactional
    public int requeuePending(int batchSize) {
        List<DeadLetterEvent> deadLetters = deadLetterEventRepository.findByStatus(
                DeadLetterEvent.DlqStatus.PENDING,
                PageRequest.of(0, batchSize));
        int requeued = 0;
        for (DeadLetterEvent deadLetter : deadLetters) {
            try {
                OutboxEvent event = new OutboxEvent();
                event.setEventType(deadLetter.getEventType());
                event.setAggregateType(deadLetter.getAggregateType());
                event.setAggregateId(deadLetter.getAggregateId());
                event.setPayload(deadLetter.getPayload());
                event.setStatus(OutboxEvent.OutboxStatus.PENDING);
                event.setRetryCount(0);
                outboxEventRepository.save(event);
                deadLetterEventRepository.markRequeued(
                        deadLetter.getId(), DeadLetterEvent.DlqStatus.REQUEUED, LocalDateTime.now());
                requeued++;
            } catch (Exception ex) {
                log.error("OUTBOX_DLQ_REQUEUE_FAILED | id={} | type={} | error={}",
                        deadLetter.getId(), deadLetter.getEventType(), ex.getMessage(), ex);
            }
        }
        if (requeued > 0) {
            log.info("OUTBOX_DLQ_REQUEUED | count={}", requeued);
        }
        return requeued;
    }

    public long countPending() {
        return deadLetterEventRepository.countByStatus(DeadLetterEvent.DlqStatus.PENDING);
    }

    // ==================== ADMIN (DLQ inspection / replay) ====================

    /**
     * Returns the most recent dead-letter events (newest first) for the admin
     * DLQ panel. Payloads are included so operators can inspect the message.
     */
    @Transactional(readOnly = true)
    public List<DeadLetterEvent> listRecent(int limit) {
        return deadLetterEventRepository.findAllOrderByCreatedAtDesc(
                PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
    }

    /**
     * Returns a single dead-letter event by id, or throws if it does not exist.
     */
    @Transactional(readOnly = true)
    public DeadLetterEvent getById(Long id) {
        return deadLetterEventRepository.findById(id)
                .orElseThrow(() -> new com.bhukkad.exception.ResourceNotFoundException(
                        "Dead-letter event not found: " + id));
    }

    /**
     * Requeues a single dead-letter event back into the outbox so the polling
     * processor retries it. Returns the requeued event. Idempotent: re-requeuing
     * an already-requeued event is a no-op returning the current row.
     */
    @Transactional
    public DeadLetterEvent requeueOne(Long id) {
        DeadLetterEvent deadLetter = getById(id);
        if (deadLetter.getStatus() == DeadLetterEvent.DlqStatus.REQUEUED) {
            log.info("OUTBOX_DLQ_ALREADY_REQUEUED | id={}", id);
            return deadLetter;
        }
        OutboxEvent event = new OutboxEvent();
        event.setEventType(deadLetter.getEventType());
        event.setAggregateType(deadLetter.getAggregateType());
        event.setAggregateId(deadLetter.getAggregateId());
        event.setPayload(deadLetter.getPayload());
        event.setStatus(OutboxEvent.OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxEventRepository.save(event);
        deadLetterEventRepository.markRequeued(
                deadLetter.getId(), DeadLetterEvent.DlqStatus.REQUEUED, LocalDateTime.now());
        deadLetter.setStatus(DeadLetterEvent.DlqStatus.REQUEUED);
        deadLetter.setRequeuedAt(LocalDateTime.now());
        log.info("OUTBOX_DLQ_REQUEUED_ONE | id={} | type={} | aggregateId={}",
                id, deadLetter.getEventType(), deadLetter.getAggregateId());
        return deadLetter;
    }
}
