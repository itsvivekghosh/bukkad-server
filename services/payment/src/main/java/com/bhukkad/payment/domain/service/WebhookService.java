package com.bhukkad.payment.domain.service;

import com.bhukkad.common.outbox.OutboxEventService;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.infrastructure.persistence.WebhookIdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bhukkad.payment.domain.entity.Payment;
import java.util.Map;

/**
 * Transactional core of the Razorpay webhook completion path (audit V-11,
 * PERF-2/D8 — wave 2-c "single-tx webhook service"; feature #1 adds the
 * legal-transition map).
 *
 * <p>The controller previously ran the side effects across three detached
 * commits: settle (tx1, committed) → {@code markProcessed}
 * ({@code REQUIRES_NEW} — burned the event id even when later steps failed) →
 * outbox enqueue in {@code try/catch} that only {@code log.warn}'d. A crash or
 * enqueue failure between settlement and the event left a SETTLED payment with
 * no event, and any provider redelivery hit the burned dedup row and was
 * swallowed — a permanent silent gap in the money trail (RC-D).</p>
 *
 * <p><strong>Contract:</strong> dedup claim, payment transition and outbox
 * enqueue execute in ONE transaction. If any step fails, everything rolls
 * back together — including the dedup claim — so the provider's retry re-runs
 * the whole unit cleanly. Concurrent duplicate deliveries lose the unique
 * {@code (scope, key)} race on the in-tx claim with a
 * {@link DataIntegrityViolationException} (transaction rolls back; caller
 * maps it to a benign duplicate response).</p>
 *
 * <p><strong>Transition map (feature #1/D3):</strong> the dedup key is the PSP
 * EVENT id — never the payment id, which collides when
 * {@code payment.captured} and {@code payment.refunded} both reference the
 * same payment. The payment status moves only along the legal map
 * (PENDING/PROCESSING/PENDING_WALLET → SETTLED, SETTLED → REFUNDED) via a
 * conditional {@code UPDATE … WHERE status = :expected}; illegal transitions
 * are no-ops counted on a metric inside {@link PaymentService}.</p>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** Outcome of {@link #completeFromWebhook}. */
    public enum Outcome {
        /** Transition applied and the event enqueued (committed together). */
        PROCESSED,
        /** Event id already (or concurrently) processed — nothing applied. */
        DUPLICATE
    }

    private final PaymentService paymentService;
    private final OutboxEventService outboxEventService;
    private final WebhookIdempotencyService webhookIdempotencyService;

    public WebhookService(PaymentService paymentService,
                          OutboxEventService outboxEventService,
                          WebhookIdempotencyService webhookIdempotencyService) {
        this.paymentService = paymentService;
        this.outboxEventService = outboxEventService;
        this.webhookIdempotencyService = webhookIdempotencyService;
    }

    /**
     * Atomically: claim the provider event id, apply the legal transition,
     * enqueue {@code PAYMENT_WEBHOOK_RECEIVED}. No step is swallowed — an
     * enqueue failure rolls the transition back with it (G-1).
     *
     * @param eventId      provider event id — the dedup key; blank disables
     *                     dedup (the transition is still atomic), mirroring the
     *                     legacy contract
     * @param targetStatus the webhook's target status ({@code SETTLED} for
     *                     {@code payment.captured}, {@code REFUNDED} for
     *                     {@code payment.refunded}); must be a legal target in
     *                     the transition map
     * @throws DataIntegrityViolationException when a concurrent delivery of
     *         the same event id wins the unique-claim race; the whole tx
     *         rolls back and the caller may answer the provider with 200
     */
    @Transactional
    public Outcome completeFromWebhook(String gatewayOrderId, String gatewayPaymentId,
                                       String eventId, String targetStatus) {
        // First-write-wins claim inside THIS transaction (unique (scope, key));
        // a concurrent duplicate rolls the whole unit back with a
        // DataIntegrityViolationException — mapped by the caller to 200.
        webhookIdempotencyService.claim(eventId);
        Payment payment = paymentService.completeWebhookPayment(
                gatewayOrderId, gatewayPaymentId, targetStatus);
        outboxEventService.enqueue("PAYMENT_WEBHOOK_RECEIVED", payment.getOrderId(),
                Map.of("eventId", eventId == null ? "" : eventId,
                        "gatewayOrderId", gatewayOrderId,
                        "gatewayPaymentId", gatewayPaymentId,
                        "paymentId", String.valueOf(payment.getId()),
                        "status", payment.getStatus()));
        log.info("WEBHOOK_COMPLETED | eventId={} | paymentId={} | orderId={} | status={}",
                eventId, payment.getId(), payment.getOrderId(), payment.getStatus());
        return Outcome.PROCESSED;
    }

    /** Fast path for known re-deliveries (the in-tx unique claim stays the race-safe guard). */
    public boolean isKnownEvent(String eventId) {
        return webhookIdempotencyService.isAlreadyProcessed(eventId);
    }
}
