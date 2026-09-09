package com.bhukkad.payment.idempotency;

import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
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
 * {@code completeWebhookPayment} twice, which is a money-path correctness risk.</p>
 *
 * <p><strong>PERF-2/V-11 rework:</strong> the old {@code REQUIRES_NEW}
 * {@code markProcessed} deliberately committed the event id BEFORE the
 * settlement/enqueue of the same delivery — burning the dedup token on paths
 * that then failed and making provider retries un-retryable. The claim now
 * runs {@code MANDATORY}: it commits inside the caller's business transaction
 * ({@link com.bhukkad.payment.service.WebhookService#completeFromWebhook}),
 * so settlement + dedup + event are one atomic unit. A concurrent duplicate
 * delivery surfaces as {@code DataIntegrityViolationException} from the unique
 * {@code (scope, key)} index, rolls that transaction back and is answered with
 * a benign duplicate response.</p>
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
     * processed. Fast path for redeliveries — the in-tx claim remains the
     * race-safe guarantee this check only avoids repeating work.
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
     * Claims a provider event id inside the caller's business transaction
     * (first-write-wins via the unique index). Blank event ids carry no
     * dedup token; the caller proceeds without one.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException on a
     *         concurrent duplicate delivery — the caller's transaction rolls
     *         back; do NOT catch this inside a transactional scope (the tx is
     *         already rollback-only at that point and the commit would
     *         surface as {@code UnexpectedRollbackException}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claim(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            return;
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(eventId);
        record.setScope(IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setExpiresAt(LocalDateTime.now().plus(WEBHOOK_TTL));
        idempotencyRecordRepository.saveAndFlush(record);
    }
}
