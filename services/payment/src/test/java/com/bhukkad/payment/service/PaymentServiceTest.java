package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.domain.WalletBalanceRepository;
import com.bhukkad.payment.domain.WalletTransaction;
import com.bhukkad.payment.domain.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private WalletBalanceRepository walletBalanceRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private IdempotencyRecordRepository idempotencyRepository;
    @Mock private PaymentEventPublisher eventPublisher;

    @InjectMocks private PaymentService service;

    @Test
    void processPayment_settlesWithoutWalletTouchForNonWalletMethod() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-1")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(1L);
            }
            return p;
        });

        Payment payment = service.processPayment(10L, 1L, new BigDecimal("100.00"), "UPI", "key-1");

        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_SETTLED);
        assertThat(payment.getProviderRef()).startsWith("PROV-");
        // Non-wallet methods must never move wallet balances.
        verify(walletBalanceRepository, times(0)).save(any(WalletBalance.class));
        verify(eventPublisher).paymentSettled(1L, 10L, 1L, new BigDecimal("100.00"));
    }

    @Test
    void processPayment_walletMethod_debitsWallet() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-w2")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(7L);
            }
            return p;
        });
        WalletBalance existing = new WalletBalance();
        existing.setCustomerId(1L);
        existing.setBalance(new BigDecimal("60.00"));
        when(walletBalanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(existing));
        when(walletTransactionRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        Payment payment = service.processPayment(10L, 1L, new BigDecimal("40.00"),
                Payment.METHOD_WALLET, "key-w2");

        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_SETTLED);
        // A WALLET payment spends credit: balance decremented, ledger DEBIT row.
        assertThat(existing.getBalance()).isEqualByComparingTo("20.00");
        // M-1: the DEBIT row's balance_after is the real remaining balance.
        var txCaptor = org.mockito.ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getBalanceAfter()).isEqualByComparingTo("20.00");
        verify(eventPublisher).paymentSettled(7L, 10L, 1L, new BigDecimal("40.00"));
    }

    @Test
    void processPayment_walletMethod_insufficient_throws() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-w3")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(8L);
            return p;
        });
        WalletBalance existing = new WalletBalance();
        existing.setCustomerId(1L);
        existing.setBalance(new BigDecimal("5.00"));
        when(walletBalanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.processPayment(10L, 1L, new BigDecimal("40.00"),
                Payment.METHOD_WALLET, "key-w3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient");
    }

    @Test
    void processPayment_negativeAmount_throws() {
        assertThatThrownBy(() -> service.processPayment(10L, 1L, new BigDecimal("-40.00"),
                "UPI", "key-neg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void processPayment_idempotentReplay_returnsCachedWithoutReCredit() {
        IdempotencyRecord completed = new IdempotencyRecord();
        completed.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-dup")).thenReturn(Optional.of(completed));
        Payment existing = new Payment();
        existing.setId(5L);
        when(paymentRepository.findByOrderId(20L)).thenReturn(Optional.of(existing));

        Payment payment = service.processPayment(20L, 1L, new BigDecimal("100.00"), "UPI", "key-dup");

        assertThat(payment.getId()).isEqualTo(5L);
        // No double-credit: save on payment and wallet must not be called
        verify(paymentRepository, times(0)).save(any(Payment.class));
        verify(walletBalanceRepository, times(0)).save(any(WalletBalance.class));
    }

    @Test
    void processPayment_idempotentReplay_missingPayment_throws() {
        IdempotencyRecord completed = new IdempotencyRecord();
        completed.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-dup")).thenReturn(Optional.of(completed));
        when(paymentRepository.findByOrderId(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processPayment(20L, 1L, new BigDecimal("10.00"), "UPI", "key-dup"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already processed");
    }

    @Test
    void processPayment_nonWalletMethod_neverTouchesWallet() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-wallet")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(2L);
            return p;
        });

        service.processPayment(10L, 1L, new BigDecimal("25.00"), "UPI", "key-wallet");

        verify(walletBalanceRepository, times(0)).findByCustomerIdForUpdate(any());
        verify(walletBalanceRepository, times(0)).save(any(WalletBalance.class));
    }

    @Test
    void processPayment_idempotencyInProgress_proceeds() {
        IdempotencyRecord inProgress = new IdempotencyRecord();
        inProgress.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "key-ip")).thenReturn(Optional.of(inProgress));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(3L);
            return p;
        });

        Payment payment = service.processPayment(10L, 1L, new BigDecimal("30.00"), "UPI", "key-ip");

        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_SETTLED);
    }

    @Test
    void getPayment_found_returnsPayment() {
        Payment payment = new Payment();
        payment.setId(1L);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThat(service.getPayment(1L).getId()).isEqualTo(1L);
    }

    @Test
    void getPayment_notFound_throws() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPayment(99L))
                .isInstanceOf(com.bhukkad.common.error.ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }
}
