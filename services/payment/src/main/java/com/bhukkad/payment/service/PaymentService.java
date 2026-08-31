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

/**
 * Payment processing and settlement. The idempotency record (scope=PAYMENT_PROCESS)
 * prevents double-credit: the first call with a given idempotency key wins;
 * duplicates return the cached result (plan §6.1/§13 — single-writer money path).
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final PaymentEventPublisher eventPublisher;

    @Transactional
    public Payment processPayment(Long orderId, Long customerId, BigDecimal amount, String idempotencyKey) {
        // Idempotency guard: check if this idempotency key was already completed
        var existing = idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, idempotencyKey);
        if (existing.isPresent() && existing.get().getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED) {
            return paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new BusinessException("Payment already processed but not found"));
        }

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setCustomerId(customerId);
        payment.setAmount(amount);
        payment.setStatus(Payment.STATUS_PENDING);
        payment.setProvider("simulated");
        payment = paymentRepository.save(payment);
        final Long paymentId = payment.getId();

        // Simulate external provider call — always succeeds
        payment.setStatus(Payment.STATUS_SETTLED);
        payment.setProviderRef("PROV-" + paymentId);
        paymentRepository.save(payment);

        // Credit wallet
        walletBalanceRepository.findByCustomerId(customerId)
                .ifPresentOrElse(wb -> {
                    wb.setBalance(wb.getBalance().add(amount));
                    walletBalanceRepository.save(wb);
                }, () -> {
                    WalletBalance wb = new WalletBalance();
                    wb.setCustomerId(customerId);
                    wb.setBalance(amount);
                    walletBalanceRepository.save(wb);
                });

        WalletTransaction tx = new WalletTransaction();
        tx.setCustomerId(customerId);
        tx.setType("CREDIT");
        tx.setAmount(amount);
        tx.setBalanceAfter(calculateBalanceAfter(customerId, amount));
        tx.setReference("PAYMENT-" + paymentId);
        walletTransactionRepository.save(tx);

        // Record idempotency — mark COMPLETED with response payload
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setScope(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS);
        record.setOwnerId(customerId);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{\"paymentId\":%d}".formatted(paymentId));
        record.setExpiresAt(LocalDateTime.now().plusDays(1));
        idempotencyRepository.save(record);

        eventPublisher.paymentSettled(paymentId, orderId, customerId, amount);
        eventPublisher.walletCredited(customerId, amount, tx.getId());

        return payment;
    }

    private BigDecimal calculateBalanceAfter(Long customerId, BigDecimal credit) {
        return walletBalanceRepository.findByCustomerId(customerId)
                .map(wb -> wb.getBalance().add(credit))
                .orElse(credit);
    }

    @Transactional(readOnly = true)
    public Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
    }
}