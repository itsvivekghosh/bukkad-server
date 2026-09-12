package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.repository.PaymentRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalPaymentServiceReplayTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private IdempotencyRecordRepository idempotencyRepository;
    @Mock private AutoRefundService refundService;
    @Mock private WalletService walletService;
    @InjectMocks private InternalPaymentService service;

    private static InternalPaymentService.ChargeCommand command(String method) {
        return new InternalPaymentService.ChargeCommand(
                1L, 2L, new BigDecimal("10.00"), method, "ref-1");
    }

    @Test
    void charge_nullPaymentMethod_failsUnknownMethodGuard() {
        assertThatThrownBy(() -> service.charge(command(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown payment method");

        verify(idempotencyRepository, never()).findByScopeAndIdempotencyKey(any(), any());
    }

    @Test
    void charge_padsAndUppercasesMethod() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                eq(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS), eq("internal-charge:ref-1")))
                .thenReturn(Optional.of(new IdempotencyRecord()));
        Payment existing = new Payment();
        existing.setId(55L);
        when(paymentRepository.findByIdempotencyKey("ref-1")).thenReturn(Optional.of(existing));

        InternalPaymentService.SagaOutcome outcome = service.charge(command("  upi "));

        assertThat(outcome.status()).isEqualTo(InternalPaymentService.STATUS_CHARGED);
        assertThat(outcome.paymentId()).isEqualTo(55L);
    }

    @Test
    void charge_replayWithoutPaymentRow_surfacesBusinessError() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                eq(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS), eq("internal-charge:ref-1")))
                .thenReturn(Optional.of(new IdempotencyRecord()));
        when(paymentRepository.findByIdempotencyKey("ref-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.charge(command("UPI")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already processed");
    }

    @Test
    void refund_nullPaymentId_throws() {
        assertThatThrownBy(() -> service.refund(null, "why"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("paymentId");
    }
}
