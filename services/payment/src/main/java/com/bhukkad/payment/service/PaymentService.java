package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.domain.WalletBalanceRepository;
import com.bhukkad.payment.domain.WalletTransaction;
import com.bhukkad.payment.domain.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final PaymentEventPublisher eventPublisher;

    @Transactional
    public Payment processPayment(Long orderId, Long customerId, BigDecimal amount,
                                  String paymentMethod, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            // Rejects zero/negative "payments" that previously drained or
            // fabricated wallet balances via the credit path below.
            throw new BusinessException("Payment amount must be positive");
        }
        var existing = idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, idempotencyKey);
        if (existing.isPresent() && existing.get().getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED) {
            return paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new BusinessException("Payment already processed but not found"));
        }

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setCustomerId(customerId);
        payment.setPurpose(Payment.PURPOSE_ORDER);
        payment.setPaymentMethod(paymentMethod);
        payment.setAmount(amount);
        payment.setStatus(Payment.STATUS_PENDING);
        payment = paymentRepository.save(payment);
        final Long paymentId = payment.getId();

        payment.setStatus(Payment.STATUS_SETTLED);
        payment.setGatewayAmount(amount);
        payment.setProviderRef("PROV-" + paymentId);
        payment = paymentRepository.save(payment);

        // METHOD_WALLET semantics: a wallet payment spends existing credit —
        // it must DEBIT the wallet, not credit it (crediting turned every
        // "wallet payment" into free money).
        if (Payment.METHOD_WALLET.equals(paymentMethod)) {
            WalletBalance wb = walletBalanceRepository.findByCustomerIdForUpdate(customerId)
                    .orElseThrow(() -> new BusinessException("No wallet for customer " + customerId));
            if (wb.getBalance().compareTo(amount) < 0) {
                throw new BusinessException("Insufficient wallet balance");
            }
            wb.setBalance(wb.getBalance().subtract(amount));
            walletBalanceRepository.save(wb);

            WalletTransaction tx = new WalletTransaction();
            tx.setCustomerId(customerId);
            tx.setType("DEBIT");
            tx.setAmount(amount);
            // Ledger value captured from the post-debit entity (never
            // recomputed — the old calculateBalanceAfter double-counted the
            // movement and corrupted reconciliation).
            tx.setBalanceAfter(wb.getBalance());
            tx.setReference("PAYMENT-" + paymentId);
            walletTransactionRepository.save(tx);
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setScope(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS);
        record.setOwnerId(customerId);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{\"paymentId\":%d}".formatted(paymentId));
        record.setExpiresAt(LocalDateTime.now().plusDays(1));
        idempotencyRepository.save(record);

        eventPublisher.paymentSettled(paymentId, orderId, customerId, amount);

        return payment;
    }

    /**
     * Marks a payment captured after a verified Razorpay {@code payment.captured}
     * webhook. Matches by providerRef (gateway payment id) first, then by
     * provider order reference. Idempotency is handled upstream by
     * {@link com.bhukkad.payment.idempotency.WebhookIdempotencyService}.
     */
    @Transactional
    public Payment completeWebhookPayment(String gatewayOrderId, String gatewayPaymentId) {
        Payment payment = paymentRepository.findByProviderRef(gatewayPaymentId)
                .or(() -> paymentRepository.findByProviderRef(gatewayOrderId))
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException(
                        "No payment found for gateway order/payment: "
                                + gatewayOrderId + "/" + gatewayPaymentId));
        payment.setStatus(Payment.STATUS_SETTLED);
        if (gatewayPaymentId != null && !gatewayPaymentId.isBlank()) {
            payment.setProviderRef(gatewayPaymentId);
        }
        Payment saved = paymentRepository.save(payment);
        eventPublisher.paymentSettled(saved.getId(), saved.getOrderId(),
                saved.getCustomerId(), saved.getAmount());
        return saved;
    }

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