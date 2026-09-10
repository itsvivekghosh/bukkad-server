package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.PaymentGatewayException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import com.bhukkad.payment.gateway.PaymentGateway;
import com.bhukkad.payment.idempotency.PaymentScopeIdempotencyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payment charge + webhook transition core (audit feature #1, roadmap §1).
 *
 * <p><strong>Idempotent charge ({@link #processPayment}):</strong> a
 * {@code PAYMENT_CHARGE} idempotency row is inserted FIRST (IN_PROGRESS, its
 * own committed transaction so concurrent duplicates observe it), then the PSP
 * charge runs OUTSIDE any transaction, then providerRef + SETTLED + the
 * {@code payment_settled} outbox event commit in ONE transaction (the G-1
 * guard on {@link OutboxClient} enforces the ambient tx). A duplicate request
 * fetches the stored payload and returns the stored payment — never a second
 * charge, never a second wallet movement. The wallet CREDIT (top-up purpose)
 * runs only AFTER the settled row + outbox commit, in its own transaction that
 * atomically flips the claim payload's {@code walletMovement} flag so a crash
 * between settle and credit replays the credit exactly once. A METHOD_WALLET
 * order payment DEBITS inside the settle transaction (all-or-nothing with the
 * settlement, preserving the M-1 semantics).</p>
 *
 * <p><strong>Webhook transitions ({@link #completeWebhookPayment}):</strong> a
 * legal-transition map (PENDING/PROCESSING/PENDING_WALLET → SETTLED,
 * SETTLED → REFUNDED) applied via a conditional
 * {@code UPDATE … WHERE status = :expected}; illegal transitions are no-ops
 * counted on the {@code payment.webhook.illegal.transitions} metric. Dedup
 * keying is the PSP EVENT id (claimed by {@code WebhookService} via the
 * {@code RAZORPAY_WEBHOOK} scope) — never the payment id, which collides
 * across {@code payment.captured}/{@code payment.refunded} deliveries.</p>
 */
@Slf4j
@Service
public class PaymentService {

    /** Batch-contract scope for the idempotent charge claim (raw string: not a platform-lib enum member). */
    public static final String SCOPE_PAYMENT_CHARGE = "PAYMENT_CHARGE";
    /** Batch-contract event type emitted on settlement (W1 async saga contract). */
    public static final String EVENT_PAYMENT_SETTLED = "payment_settled";
    /** Batch-contract event type emitted when a saga charge fails. */
    public static final String EVENT_PAYMENT_FAILED = "payment_failed";

    static final Duration CLAIM_TTL = Duration.ofHours(24);

    /** Legal webhook transitions: target status → the source statuses it may come from. */
    static final Map<String, Set<String>> LEGAL_WEBHOOK_TRANSITIONS = Map.of(
            Payment.STATUS_SETTLED, Set.of(Payment.STATUS_PENDING, Payment.STATUS_PROCESSING,
                    Payment.STATUS_PENDING_WALLET),
            Payment.STATUS_REFUNDED, Set.of(Payment.STATUS_SETTLED));

    private static final Pattern PAYMENT_ID_PAYLOAD = Pattern.compile("\"paymentId\":(\\d+)");
    private static final Pattern WALLET_DONE_PAYLOAD = Pattern.compile("\"walletMovement\":(true|false)");

    private final PaymentRepository paymentRepository;
    private final WalletService walletService;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final PaymentScopeIdempotencyRepository paymentScopeIdempotencyRepository;
    private final OutboxClient outboxClient;
    private final PaymentGateway paymentGateway;
    private final PaymentPropertiesGateway currencyResolver;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** Minimal currency source so PaymentService does not depend on the full properties type. */
    public interface PaymentPropertiesGateway {
        String currency();
    }

    public PaymentService(PaymentRepository paymentRepository,
                          WalletService walletService,
                          IdempotencyRecordRepository idempotencyRepository,
                          PaymentScopeIdempotencyRepository paymentScopeIdempotencyRepository,
                          OutboxClient outboxClient,
                          PaymentGateway paymentGateway,
                          PaymentPropertiesGateway currencyResolver,
                          PlatformTransactionManager transactionManager,
                          ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.paymentRepository = paymentRepository;
        this.walletService = walletService;
        this.idempotencyRepository = idempotencyRepository;
        this.paymentScopeIdempotencyRepository = paymentScopeIdempotencyRepository;
        this.outboxClient = outboxClient;
        this.paymentGateway = paymentGateway;
        this.currencyResolver = currencyResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistryProvider = meterRegistryProvider;
    }

    // ── Synchronous charge path (PaymentController / wallet top-up) ──────────

    /**
     * Charges {@code amount} for an order (or a wallet top-up when
     * {@code orderId} is {@code 0}/null) exactly once per idempotency key.
     */
    public Payment processPayment(Long orderId, Long customerId, BigDecimal amount,
                                  String paymentMethod, String idempotencyKey) {
        return charge(orderId, customerId, amount, paymentMethod, idempotencyKey, false);
    }

    /**
     * Async saga entry (feature #3 pairing): identical idempotent charge
     * lifecycle, but a declined charge also emits {@code payment_failed} in the
     * SAME transaction that marks the payment + claim FAILED, so the order saga
     * always receives a terminal signal.
     *
     * @return the settled payment
     * @throws PaymentGatewayException when the PSP declined the charge
     */
    public Payment processPaymentRequested(Long orderId, Long customerId, BigDecimal amount,
                                           String currency, String idempotencyKey) {
        String configured = currencyResolver.currency();
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("payment_requested without a currency");
        }
        if (!configured.equalsIgnoreCase(currency)) {
            PaymentGatewayException e = new PaymentGatewayException(
                    "Unsupported currency " + currency + " (configured: " + configured + ")");
            failCharge(buildFailedRecord(orderId, customerId, amount, e.getMessage()), idempotencyKey, true);
            throw e;
        }
        return charge(orderId, customerId, amount, Payment.METHOD_UPI, idempotencyKey, true);
    }

    private Payment charge(Long orderId, Long customerId, BigDecimal amount,
                           String paymentMethod, String idempotencyKey, boolean emitFailedEvent) {
        if (amount == null || amount.signum() <= 0) {
            // Rejects zero/negative "payments" that previously drained or
            // fabricated wallet balances via the credit path below.
            throw new BusinessException("Payment amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency key is required");
        }
        boolean topUp = orderId == null || orderId == 0L;

        // 1. Claim FIRST (committed before any external call so concurrent
        //    duplicates observe the claim); a duplicate returns the stored payload.
        String stored = claimCharge(idempotencyKey, customerId);
        if (stored != null) {
            Payment replayed = restoreFromPayload(stored, orderId);
            if (topUp && !walletMovementDone(stored)) {
                // Crash between settle-commit and credit: the replay completes
                // the credit exactly once (flag flip is in the credit's tx).
                completeTopUpCredit(customerId, amount, replayed, idempotencyKey);
            }
            return replayed;
        }

        // 2. Persist the PENDING row so the PSP charge carries a payment id,
        //    then charge OUTSIDE any transaction (no DB connection held across
        //    the slow external call).
        Payment pending = new Payment();
        pending.setOrderId(topUp ? 0L : orderId);
        pending.setCustomerId(customerId);
        pending.setPurpose(topUp ? Payment.PURPOSE_WALLET_TOP_UP : Payment.PURPOSE_ORDER);
        pending.setPaymentMethod(paymentMethod);
        pending.setAmount(amount);
        pending.setStatus(Payment.STATUS_PENDING);
        pending.setIdempotencyKey(idempotencyKey);
        pending = paymentRepository.saveAndFlush(pending);
        final Payment pendingRef = pending;

        // 3. PSP charge (breaker/retry filters on the adapter's WebClient).
        PaymentGateway.GatewayResult attempt;
        try {
            attempt = paymentGateway.authorize(pending.getId(), customerId, amount, currencyResolver.currency());
        } catch (RuntimeException e) {
            log.error("PAYMENT_CHARGE_ERROR | paymentId={} | key={}", pending.getId(), idempotencyKey, e);
            attempt = PaymentGateway.GatewayResult.failed("Gateway error: " + e.getMessage());
        }
        final PaymentGateway.GatewayResult result = attempt;
        if (!result.success()) {
            failCharge(pending, idempotencyKey, emitFailedEvent);
            throw new PaymentGatewayException(result.message());
        }

        // 4. ONE transaction: SETTLED + providerRef + outbox payment_settled
        //    (+ wallet debit for METHOD_WALLET order payments — all-or-nothing).
        Payment settled = transactionTemplate.execute(status ->
                settleCharge(pendingRef, amount, paymentMethod, idempotencyKey, result, topUp));

        // 5. AFTER the settled row + outbox commit: the wallet top-up credit.
        if (topUp) {
            completeTopUpCredit(customerId, amount, settled, idempotencyKey);
        }
        log.info("PAYMENT_SETTLED | paymentId={} | orderId={} | providerRef={} | topUp={}",
                settled.getId(), settled.getOrderId(), settled.getProviderRef(), topUp);
        return settled;
    }

    /** Runs inside the settle transaction (G-1: outbox enqueue needs the ambient tx). */
    private Payment settleCharge(Payment pending, BigDecimal amount, String paymentMethod,
                                 String idempotencyKey, PaymentGateway.GatewayResult result, boolean topUp) {
        Payment payment = paymentRepository.findByIdWithLock(pending.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + pending.getId()));
        payment.setStatus(Payment.STATUS_SETTLED);
        payment.setGatewayAmount(amount);
        payment.setProviderRef(result.providerRef());
        payment.setGatewayPaymentId(result.providerRef());
        payment.setGatewayOrderId(result.gatewayOrderId());
        payment.setCompletedAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);

        // METHOD_WALLET semantics: a wallet order payment spends existing
        // credit — DEBIT inside the settle tx so an insufficient balance rolls
        // the settlement back too (never a settled payment without funds).
        if (!topUp && Payment.METHOD_WALLET.equals(paymentMethod)) {
            walletService.debit(payment.getCustomerId(), amount, "PAYMENT-" + payment.getId());
        }

        emitPaymentSettled(payment);
        // Claim → COMPLETED in the SAME tx as the settlement: a replay can only
        // observe COMPLETED once the settled row + outbox event are durable.
        paymentScopeIdempotencyRepository.transition(SCOPE_PAYMENT_CHARGE, idempotencyKey,
                IdempotencyRecord.IdempotencyStatus.COMPLETED.name(),
                chargePayload(payment.getId(), !topUp),
                LocalDateTime.now().plus(CLAIM_TTL));
        return payment;
    }

    /** Credit tx: the wallet credit and its claim-payload flag flip commit together. */
    private void completeTopUpCredit(Long customerId, BigDecimal amount, Payment payment,
                                     String idempotencyKey) {
        transactionTemplate.executeWithoutResult(status -> {
            walletService.credit(customerId, amount, "TOPUP-" + payment.getId());
            paymentScopeIdempotencyRepository.transition(SCOPE_PAYMENT_CHARGE, idempotencyKey,
                    IdempotencyRecord.IdempotencyStatus.COMPLETED.name(),
                    chargePayload(payment.getId(), true),
                    LocalDateTime.now().plus(CLAIM_TTL));
        });
    }

    /** Failure tx: payment FAILED + claim FAILED (+ saga payment_failed event) atomically. */
    private void failCharge(Payment pending, String idempotencyKey, boolean emitFailedEvent) {
        transactionTemplate.executeWithoutResult(status -> {
            paymentRepository.markFailedIfPending(pending.getId());
            paymentScopeIdempotencyRepository.transition(SCOPE_PAYMENT_CHARGE, idempotencyKey,
                    IdempotencyRecord.IdempotencyStatus.FAILED.name(),
                    "{\"error\":\"charge declined\"}",
                    LocalDateTime.now().plus(CLAIM_TTL));
            if (emitFailedEvent) {
                outboxClient.enqueue(PlatformEventMessage.of(EVENT_PAYMENT_FAILED,
                                String.valueOf(pending.getOrderId()),
                                "{\"orderId\":%d,\"paymentId\":%d,\"reason\":\"%s\"}"
                                        .formatted(pending.getOrderId(), pending.getId(),
                                                jsonEscape("charge declined"))),
                        pending.getOrderId());
            }
        });
    }

    private Payment buildFailedRecord(Long orderId, Long customerId, BigDecimal amount, String reason) {
        // Currency-mismatch short-circuit: no PENDING row was created yet, so
        // the failure payload carries a null payment id (0 sentinel).
        Payment payment = new Payment();
        payment.setOrderId(orderId == null ? 0L : orderId);
        payment.setCustomerId(customerId);
        payment.setAmount(amount);
        return payment;
    }

    /**
     * Claims the charge (first-write-wins via the unique {@code (scope, key)}
     * index). Returns the stored response payload when the key was already
     * claimed (replay / in-flight), {@code null} when this call won the claim.
     *
     * <p>Implements the batch contract "insert idempotency row first, duplicate
     → fetch stored payload → return it": the atomic
     * {@code INSERT … WHERE NOT EXISTS} covers the
     * DataIntegrityViolationException race without poisoning an ambient tx —
     * a losing insert surfaces as 0 rows, then the stored payload is fetched.</p>
     */
    private String claimCharge(String idempotencyKey, Long customerId) {
        // Legacy guard: pre-feature-#1 rows live under the old PAYMENT_PROCESS
        // scope; a replay across the deploy boundary must still dedupe.
        var legacy = idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, idempotencyKey);
        if (legacy.isPresent()
                && legacy.get().getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED) {
            return legacy.get().getResponsePayload();
        }

        int inserted = transactionTemplate.execute(status -> idempotencyRepository.insertIfAbsent(
                idempotencyKey, SCOPE_PAYMENT_CHARGE, customerId,
                IdempotencyRecord.IdempotencyStatus.IN_PROGRESS.name(), null,
                LocalDateTime.now().plus(CLAIM_TTL)));
        if (inserted > 0) {
            return null; // this call owns the charge
        }
        String storedStatus = paymentScopeIdempotencyRepository
                .findStatus(SCOPE_PAYMENT_CHARGE, idempotencyKey).orElse("");
        if (IdempotencyRecord.IdempotencyStatus.FAILED.name().equals(storedStatus)) {
            // A prior declined charge: release the claim so this retry re-runs
            // the whole unit (unique slot freed for the re-claim below).
            transactionTemplate.execute(status -> paymentScopeIdempotencyRepository
                    .deleteByScopeKeyStatus(SCOPE_PAYMENT_CHARGE, idempotencyKey,
                            IdempotencyRecord.IdempotencyStatus.FAILED.name()));
            inserted = transactionTemplate.execute(status -> idempotencyRepository.insertIfAbsent(
                    idempotencyKey, SCOPE_PAYMENT_CHARGE, customerId,
                    IdempotencyRecord.IdempotencyStatus.IN_PROGRESS.name(), null,
                    LocalDateTime.now().plus(CLAIM_TTL)));
            if (inserted > 0) {
                return null;
            }
        }
        String storedPayload = paymentScopeIdempotencyRepository
                .findResponsePayload(SCOPE_PAYMENT_CHARGE, idempotencyKey).orElse(null);
        if (storedPayload == null || !storedPayload.contains("\"paymentId\"")) {
            // Claimed but not yet completed → a concurrent request is charging.
            throw new DuplicateRequestException("Payment with this idempotency key is in progress");
        }
        return storedPayload;
    }

    private Payment restoreFromPayload(String payload, Long orderId) {
        var matcher = PAYMENT_ID_PAYLOAD.matcher(payload);
        if (matcher.find()) {
            Long paymentId = Long.valueOf(matcher.group(1));
            var byId = paymentRepository.findById(paymentId);
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        return paymentRepository.findByOrderId(orderId == null ? 0L : orderId)
                .orElseThrow(() -> new BusinessException("Payment already processed but not found"));
    }

    private boolean walletMovementDone(String payload) {
        var matcher = WALLET_DONE_PAYLOAD.matcher(payload);
        // Absent flag = legacy payload shape; treat as done (never re-credit on replay).
        return !matcher.find() || Boolean.parseBoolean(matcher.group(1));
    }

    private static String chargePayload(Long paymentId, boolean walletMovementDone) {
        return "{\"paymentId\":%d,\"walletMovement\":%s}".formatted(paymentId, walletMovementDone);
    }

    // ── Webhook transitions (D3) ─────────────────────────────────────────────

    /**
     * Marks a Razorpay webhook's legal transition on the payment. The caller
     * (WebhookService) has already claimed the PSP EVENT id — the dedup key —
     * so {@code payment.captured} and {@code payment.refunded} no longer
     * collide on the payment id.
     *
     * @param targetStatus the webhook's target ({@code SETTLED} for captured,
     *                     {@code REFUNDED} for refunded)
     * @return the transitioned payment, or {@code null} when the current
     *         status makes the transition illegal (no-op + metric — never an
     *         exception, the provider answered 200 either way)
     */
    @Transactional
    public Payment completeWebhookPayment(String gatewayOrderId, String gatewayPaymentId,
                                          String targetStatus) {
        Payment payment = paymentRepository.findByProviderRef(gatewayPaymentId)
                .or(() -> paymentRepository.findByProviderRef(gatewayOrderId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment found for gateway order/payment: "
                                + gatewayOrderId + "/" + gatewayPaymentId));
        Set<String> legalFrom = LEGAL_WEBHOOK_TRANSITIONS.get(targetStatus);
        if (legalFrom == null || !legalFrom.contains(payment.getStatus())) {
            illegalTransitions().increment();
            log.warn("WEBHOOK_ILLEGAL_TRANSITION | paymentId={} | status={} | target={}",
                    payment.getId(), payment.getStatus(), targetStatus);
            return null;
        }
        // ONE conditional UPDATE — the read status is the :expected guard; a
        // concurrent transition between the read and the update yields 0 rows
        // and the caller treats it as a no-op.
        int updated = paymentRepository.transitionStatus(payment.getId(), payment.getStatus(), targetStatus);
        if (updated == 0) {
            illegalTransitions().increment();
            log.warn("WEBHOOK_TRANSITION_LOST_RACE | paymentId={} | status={} | target={}",
                    payment.getId(), payment.getStatus(), targetStatus);
            return null;
        }
        payment.setStatus(targetStatus);
        emitPaymentSettled(payment);
        log.info("WEBHOOK_TRANSITION_APPLIED | paymentId={} | from={} | to={}",
                payment.getId(), legalFrom, targetStatus);
        return payment;
    }

    private void emitPaymentSettled(Payment payment) {
        // Payload contract (W1 saga): orderId, paymentId, providerRef + the
        // settlement details. Enqueued in the caller's transaction (G-1).
        outboxClient.enqueue(PlatformEventMessage.of(EVENT_PAYMENT_SETTLED,
                        String.valueOf(payment.getId()),
                        "{\"orderId\":%d,\"paymentId\":%d,\"customerId\":%d,\"amount\":%s,\"providerRef\":\"%s\"}"
                                .formatted(payment.getOrderId(), payment.getId(), payment.getCustomerId(),
                                        payment.getAmount(), payment.getProviderRef())),
                payment.getId());
    }

    private io.micrometer.core.instrument.Counter illegalTransitions() {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            // No-op counter keeps the money path alive in minimal test contexts.
            return new io.micrometer.core.instrument.noop.NoopCounter(
                    new io.micrometer.core.instrument.Meter.Id(
                            "payment.webhook.illegal.transitions",
                            io.micrometer.core.instrument.Tags.empty(),
                            "events", null, io.micrometer.core.instrument.Meter.Type.COUNTER));
        }
        return registry.counter("payment.webhook.illegal.transitions");
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
    }

    /** The (single) payment attached to an order — 404 when the order is unpaid. */
    @Transactional(readOnly = true)
    public Payment getPaymentByOrder(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for order: " + orderId));
    }
}
