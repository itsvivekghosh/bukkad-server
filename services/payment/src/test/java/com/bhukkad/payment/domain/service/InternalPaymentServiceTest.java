package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Order-saga contract tests (batch A): charge never moves money, replays of a
 * reference are collision-free, and refund compensates a debited wallet exactly
 * once.
 */
@ExtendWith(MockitoExtension.class)
class InternalPaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private IdempotencyRecordRepository idempotencyRepository;
    @Mock private AutoRefundService refundService;
    @Mock private WalletService walletService;

    @InjectMocks private InternalPaymentService service;

    private InternalPaymentService.ChargeCommand command(String method, String amount, String reference) {
        return new InternalPaymentService.ChargeCommand(
                55L, 7L, amount == null ? null : new BigDecimal(amount), method, reference);
    }

    private void stubPaymentSaveAssignsId(long id) {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(id);
            return p;
        });
    }

    private void stubNoExistingCharge() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS,
                InternalPaymentService.CHARGE_KEY_PREFIX + "ref-1")).thenReturn(Optional.empty());
    }

    @Test
    void charge_nonWalletMethod_createsProcessingSimulatedRowWithoutMovingMoney() {
        stubNoExistingCharge();
        stubPaymentSaveAssignsId(11L);

        InternalPaymentService.SagaOutcome outcome =
                service.charge(command("upi", "250.00", "ref-1"));

        assertThat(outcome.paymentId()).isEqualTo(11L);
        assertThat(outcome.status()).isEqualTo("CHARGED");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        Payment stored = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(stored.getStatus()).isEqualTo(Payment.STATUS_PROCESSING);
        assertThat(stored.getProviderRef()).isEqualTo("SIMULATED-11");
        assertThat(stored.getPaymentMethod()).isEqualTo("UPI");
        assertThat(stored.getOrderId()).isEqualTo(55L);
        assertThat(stored.getCustomerId()).isEqualTo(7L);
        assertThat(stored.getAmount()).isEqualByComparingTo("250.00");
        assertThat(stored.getIdempotencyKey()).isEqualTo("ref-1");

        ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRepository).save(record.capture());
        assertThat(record.getValue().getIdempotencyKey()).isEqualTo("internal-charge:ref-1");
        assertThat(record.getValue().getStatus()).isEqualTo(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        assertThat(record.getValue().getResponsePayload()).isEqualTo("{\"paymentId\":11}");

        // The charge is bookkeeping only: no wallet movement, no gateway path.
        verifyNoInteractions(walletService);
    }

    @Test
    void charge_walletMethod_createsPendingWalletRow_andNeverDebitsTheWallet() {
        stubNoExistingCharge();
        stubPaymentSaveAssignsId(12L);

        InternalPaymentService.SagaOutcome outcome =
                service.charge(command("WALLET", "99.00", "ref-1"));

        assertThat(outcome.status()).isEqualTo("CHARGED");
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment stored = captor.getValue();
        assertThat(stored.getStatus()).isEqualTo(Payment.STATUS_PENDING_WALLET);
        assertThat(stored.getProviderRef()).isNull();
        // Contract: the order-side saga performs the wallet debit elsewhere.
        verifyNoInteractions(walletService);
    }

    @Test
    void charge_sameReferenceTwice_returnsSamePaymentIdWithNoSecondRow() {
        IdempotencyRecord completed = new IdempotencyRecord();
        completed.setScope(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS);
        completed.setIdempotencyKey(InternalPaymentService.CHARGE_KEY_PREFIX + "ref-1");
        completed.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "internal-charge:ref-1"))
                .thenReturn(Optional.of(completed));
        Payment original = new Payment();
        original.setId(11L);
        when(paymentRepository.findByIdempotencyKey("ref-1")).thenReturn(Optional.of(original));

        InternalPaymentService.SagaOutcome outcome =
                service.charge(command("UPI", "250.00", "ref-1"));

        assertThat(outcome.paymentId()).isEqualTo(11L);
        assertThat(outcome.status()).isEqualTo("CHARGED");
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(idempotencyRepository, never()).save(any(IdempotencyRecord.class));
        verifyNoInteractions(walletService);
    }

    @Test
    void charge_zeroAmount_throws() {
        assertThatThrownBy(() -> service.charge(command("UPI", "0.00", "ref-9")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
        verifyNoInteractions(paymentRepository, idempotencyRepository);
    }

    @Test
    void charge_unknownMethod_throws() {
        assertThatThrownBy(() -> service.charge(command("BITCOIN", "50.00", "ref-9")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown payment method");
        verifyNoInteractions(paymentRepository, idempotencyRepository);
    }

    @Test
    void charge_missingOrderOrCustomer_throws() {
        assertThatThrownBy(() -> service.charge(
                new InternalPaymentService.ChargeCommand(null, 7L, new BigDecimal("50.00"), "UPI", "ref-9")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("required");
    }

    @Test
    void charge_missingReference_throws() {
        assertThatThrownBy(() -> service.charge(command("UPI", "50.00", "  ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void refund_walletPaymentThatWasSettled_creditsWalletOnce() {
        Payment settledWallet = walletPayment(Payment.STATUS_SETTLED);
        when(refundService.sagaRefund(7L, "order cancelled"))
                .thenReturn(new AutoRefundService.SagaRefund(settledWallet, Payment.STATUS_SETTLED));

        InternalPaymentService.SagaOutcome outcome = service.refund(7L, "order cancelled");

        assertThat(outcome.paymentId()).isEqualTo(7L);
        assertThat(outcome.status()).isEqualTo(Payment.STATUS_REFUNDED);
        verify(walletService).credit(7L, new BigDecimal("199.00"), "SAGA-REFUND:7");
    }

    @Test
    void refund_doubleRefundCall_creditsWalletOnlyOnce() {
        Payment first = walletPayment(Payment.STATUS_SETTLED);
        Payment second = walletPayment(Payment.STATUS_REFUNDED);
        when(refundService.sagaRefund(7L, "x"))
                .thenReturn(new AutoRefundService.SagaRefund(first, Payment.STATUS_SETTLED))
                .thenReturn(new AutoRefundService.SagaRefund(second, null));

        InternalPaymentService.SagaOutcome one = service.refund(7L, "x");
        InternalPaymentService.SagaOutcome two = service.refund(7L, "x");

        // Same response on replay, and the second call compensates nothing.
        assertThat(two).isEqualTo(one);
        verify(walletService, org.mockito.Mockito.times(1))
                .credit(any(), any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refund_pendingWalletCharge_neverCreditsTheWallet() {
        Payment pendingWallet = walletPayment(Payment.STATUS_PENDING_WALLET);
        // A WALLET charge row was never debited; refunding it must not mint money.
        when(refundService.sagaRefund(7L, "saga abort"))
                .thenReturn(new AutoRefundService.SagaRefund(pendingWallet, Payment.STATUS_PENDING_WALLET));

        service.refund(7L, "saga abort");

        verifyNoInteractions(walletService);
    }

    @Test
    void refund_nonWalletPayment_neverCreditsWallet() {
        Payment upi = walletPayment(Payment.STATUS_SETTLED);
        upi.setPaymentMethod(Payment.METHOD_UPI);
        when(refundService.sagaRefund(7L, "x"))
                .thenReturn(new AutoRefundService.SagaRefund(upi, Payment.STATUS_SETTLED));

        service.refund(7L, "x");

        verifyNoInteractions(walletService);
    }

    @Test
    void refund_unknownPayment_propagatesNotFound() {
        when(refundService.sagaRefund(99L, "x"))
                .thenThrow(new ResourceNotFoundException("Payment not found: 99L"));

        assertThatThrownBy(() -> service.refund(99L, "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Payment walletPayment(String status) {
        Payment p = new Payment();
        p.setId(7L);
        p.setOrderId(55L);
        p.setCustomerId(7L);
        p.setAmount(new BigDecimal("199.00"));
        p.setPaymentMethod(Payment.METHOD_WALLET);
        p.setStatus(status);
        return p;
    }
}
