package com.bhukkad.payment.idempotency;

import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
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
 * <p>Port of the monolith's {@code WebhookIdempotencyService}; the record lives
 * in the payment service's own DB (platform-lib V1 creates
 * {@code idempotency_records}).</p>
 */
@Service
public class WebhookIdempotencyService {

    private static final Duration WEBHOOK_TTL = Duration.ofHours(48);

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public WebhookIdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

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
     * <p>Throws {@link DataIntegrityViolationException} on concurrent duplicate
     * delivery — deliberately, so it propagates out of this {@code REQUIRES_NEW}
     * transaction and the duplicate insert rolls back cleanly. Do NOT catch it
     * inside this method: a caught exception in a transactional method leaves
     * the transaction rollback-only and surfaces as
     * {@code UnexpectedRollbackException} at the caller's commit.</p>
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