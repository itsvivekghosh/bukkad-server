package com.bhukkad.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Deduplicates inbound payment webhooks (e.g. Razorpay {@code payment.captured}).
 *
 * <p>Payment providers may deliver the same event more than once (network retry,
 * provider redelivery). Without dedup, the same webhook would run
 * {@code completeWebhookPayment} twice, which is a money-path correctness risk.
 *
 * <p>The event id is stored in the shared {@code idempotency_records} table under
 * the {@link IdempotencyRecord.IdempotencyScope#RAZORPAY_WEBHOOK} scope. The
 * unique key {@code (scope, idempotency_key)} makes the first-write-wins check
 * atomic even under concurrent delivery of the same event.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIdempotencyService {

    private static final Duration WEBHOOK_TTL = Duration.ofHours(48);

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    /**
     * Returns {@code true} when the given provider event id has already been
     * processed. Call this before applying a webhook's side effects.
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            return false;
        }
        return idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                        IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, eventId)
                .isPresent();
    }

    /**
     * Marks a provider event id as processed. Callers must invoke this BEFORE
     * applying webhook side effects.
     *
     * <p>Returns {@code true} if this call was the first to mark the event.
     * If the event was already recorded (concurrent delivery or replay), a
     * {@link DataIntegrityViolationException} is thrown — deliberately, so it
     * propagates out of this {@code REQUIRES_NEW} transaction and the duplicate
     * insert is rolled back cleanly. The caller catches that exception to
     * acknowledge the duplicate without re-applying side effects.</p>
     *
     * <p>Do NOT catch {@link DataIntegrityViolationException} inside this method:
     * a caught exception inside a transactional method leaves the transaction
     * marked rollback-only, which surfaces as
     * {@code UnexpectedRollbackException} (HTTP 500) at the caller's commit.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            return true; // nothing to dedupe; let the caller proceed
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(eventId);
        record.setScope(IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setExpiresAt(LocalDateTime.now().plus(WEBHOOK_TTL));
        idempotencyRecordRepository.saveAndFlush(record);
        return true;
    }
}
