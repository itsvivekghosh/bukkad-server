package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Order-saga money surface (batch A contract): charges and compensating
 * refunds executed by trusted mesh services, never by end users.
 *
 * <p>A charge records a Payment row for an order line but NEVER moves money:
 * non-wallet methods land in PROCESSING with a simulated provider reference
 * (no gateway calls in this build), and WALLET charges land in
 * PENDING_WALLET because the wallet debit is the order-saga's own step,
 * executed elsewhere via {@code /api/v1/internal/wallet/debit}. The saga
 * compensates with {@link #refund(Long, String)}, which refunds a WALLET
 * payment back to the wallet exactly once (guarded by the row lock +
 * REFUNDED state).</p>
 */
@Service
@RequiredArgsConstructor
public class InternalPaymentService {

    /** Saga-facing outcome of a successful charge ("the charge landed"). */
    public static final String STATUS_CHARGED = "CHARGED";

    /**
     * Idempotency-records key prefix. The PAYMENT_PROCESS scope stays
     * compatible with {@link PaymentService}; the prefix keeps internal
     * charge references disjoint from user {@code Idempotency-Key} headers.
     */
    static final String CHARGE_KEY_PREFIX = "internal-charge:";

    private static final Set<String> SUPPORTED_METHODS = Set.of(
            Payment.METHOD_UPI, Payment.METHOD_CREDIT_CARD, Payment.METHOD_DEBIT_CARD,
            Payment.METHOD_CASH_ON_DELIVERY, Payment.METHOD_NET_BANKING,
            Payment.METHOD_BNPL, Payment.METHOD_WALLET);

    private static final String PROVIDER_SIMULATED = "SIMULATED";

    private final PaymentRepository paymentRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final AutoRefundService refundService;
    private final WalletService walletService;

    /** Validated charge inputs coming from the order saga (see the controller DTO). */
    public record ChargeCommand(Long orderId, Long customerId, BigDecimal amount,
                                String paymentMethod, String reference) {
    }

    /** {@code {"paymentId":N,"status":"CHARGED"}} on the wire. */
    public record SagaOutcome(Long paymentId, String status) {
    }

    @Transactional
    public SagaOutcome charge(ChargeCommand command) {
        if (command.orderId() == null || command.customerId() == null) {
            throw new BusinessException("orderId and customerId are required");
        }
        if (command.reference() == null || command.reference().isBlank()) {
            throw new BusinessException("reference is required");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new BusinessException("amount must be positive");
        }
        String method = command.paymentMethod() == null ? "" :
                command.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(method)) {
            throw new BusinessException("Unknown payment method: " + command.paymentMethod());
        }

        String chargeKey = CHARGE_KEY_PREFIX + command.reference();
        Optional<IdempotencyRecord> existing = idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, chargeKey);
        if (existing.isPresent()) {
            // Replay of the same reference: same paymentId, never a second row.
            Payment replay = paymentRepository.findByIdempotencyKey(command.reference())
                    .orElseThrow(() -> new BusinessException(
                            "Charge already processed but payment not found"));
            return new SagaOutcome(replay.getId(), STATUS_CHARGED);
        }

        boolean walletMethod = Payment.METHOD_WALLET.equals(method);
        Payment payment = new Payment();
        payment.setOrderId(command.orderId());
        payment.setCustomerId(command.customerId());
        payment.setPurpose(Payment.PURPOSE_ORDER);
        payment.setPaymentMethod(method);
        payment.setAmount(command.amount());
        payment.setIdempotencyKey(command.reference());
        // No gateway call, no wallet debit — PROCESSING/PENDING_WALLET rows
        // are the saga's bookkeeping handles.
        payment.setStatus(walletMethod ? Payment.STATUS_PENDING_WALLET : Payment.STATUS_PROCESSING);
        payment = paymentRepository.save(payment);
        if (!walletMethod) {
            payment.setProvider(PROVIDER_SIMULATED);
            payment.setProviderRef(PROVIDER_SIMULATED + "-" + payment.getId());
            payment = paymentRepository.save(payment);
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(chargeKey);
        record.setScope(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS);
        record.setOwnerId(command.customerId());
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{\"paymentId\":%d}".formatted(payment.getId()));
        record.setExpiresAt(LocalDateTime.now().plusDays(1));
        // (scope, key) unique constraint is the authoritative first-write-wins
        // guard: a concurrent duplicate loses with a constraint violation (409).
        idempotencyRepository.save(record);

        return new SagaOutcome(payment.getId(), STATUS_CHARGED);
    }

    @Transactional
    public SagaOutcome refund(Long paymentId, String reason) {
        if (paymentId == null) {
            throw new BusinessException("paymentId is required");
        }
        // Locks + validates inside its own status guard; priorStatus is null
        // for an already-refunded row, so the wallet credit below runs at
        // most once even under concurrent replay.
        AutoRefundService.SagaRefund result = refundService.sagaRefund(paymentId, reason);
        if (result.previousStatus() == null) {
            return new SagaOutcome(result.payment().getId(), Payment.STATUS_REFUNDED);
        }
        Payment payment = result.payment();
        // Only a wallet payment that reached SETTLED was ever debited (a
        // PENDING_WALLET charge never touched the balance) — compensate once.
        boolean walletWasDebited = Payment.METHOD_WALLET.equals(payment.getPaymentMethod())
                && Payment.STATUS_SETTLED.equals(result.previousStatus());
        if (walletWasDebited) {
            walletService.credit(payment.getCustomerId(), payment.getAmount(),
                    "SAGA-REFUND:" + payment.getId());
        }
        return new SagaOutcome(payment.getId(), Payment.STATUS_REFUNDED);
    }
}
