package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.repository.PaymentRepository;
import com.bhukkad.payment.domain.event.PaymentEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoRefundServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentEventPublisher eventPublisher;
    @InjectMocks private AutoRefundService service;

    private Payment payment(String status) {
        Payment p = new Payment();
        p.setId(1L);
        p.setOrderId(10L);
        p.setCustomerId(2L);
        p.setAmount(new BigDecimal("100.00"));
        p.setStatus(status);
        return p;
    }

    @Test
    void refund_settledPayment_marksRefundedAndPublishes() {
        Payment settled = payment(Payment.STATUS_SETTLED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(settled));

        Payment result = service.refund(1L, "customer request");

        assertThat(result.getStatus()).isEqualTo(Payment.STATUS_REFUNDED);
        verify(eventPublisher).paymentSettled(1L, 10L, 2L, new BigDecimal("100.00"));
    }

    @Test
    void refund_pendingPayment_throws() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(Payment.STATUS_PENDING)));
        assertThatThrownBy(() -> service.refund(1L, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("settled");
    }

    @Test
    void refund_unknownPayment_throws() {
        when(paymentRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refund(9L, "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // sagaRefund — internal charge/refund contract (batch A).
    // ------------------------------------------------------------------

    @Test
    void sagaRefund_settled_marksRefundedAndReportsPreviousStatus() {
        Payment settled = payment(Payment.STATUS_SETTLED);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(settled));
        when(paymentRepository.save(org.mockito.ArgumentMatchers.any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AutoRefundService.SagaRefund result = service.sagaRefund(1L, "order cancelled");

        assertThat(result.payment().getStatus()).isEqualTo(Payment.STATUS_REFUNDED);
        assertThat(result.previousStatus()).isEqualTo(Payment.STATUS_SETTLED);
        verify(eventPublisher).paymentSettled(1L, 10L, 2L, new BigDecimal("100.00"));
    }

    @Test
    void sagaRefund_internalChargeStates_markRefunded() {
        Payment processing = payment(Payment.STATUS_PROCESSING);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(processing));
        when(paymentRepository.save(org.mockito.ArgumentMatchers.any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AutoRefundService.SagaRefund result = service.sagaRefund(1L, "saga compensation");

        assertThat(result.payment().getStatus()).isEqualTo(Payment.STATUS_REFUNDED);
        assertThat(result.previousStatus()).isEqualTo(Payment.STATUS_PROCESSING);
    }

    @Test
    void sagaRefund_pendingWallet_marksRefunded() {
        Payment pendingWallet = payment(Payment.STATUS_PENDING_WALLET);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(pendingWallet));
        when(paymentRepository.save(org.mockito.ArgumentMatchers.any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.sagaRefund(1L, "saga abort").previousStatus())
                .isEqualTo(Payment.STATUS_PENDING_WALLET);
    }

    @Test
    void sagaRefund_replay_returnsSamePaymentWithoutSecondTransition() {
        Payment refunded = payment(Payment.STATUS_REFUNDED);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(refunded));

        AutoRefundService.SagaRefund result = service.sagaRefund(1L, "order cancelled");

        // Null previous status is the exactly-once signal for compensation.
        assertThat(result.previousStatus()).isNull();
        assertThat(result.payment().getId()).isEqualTo(1L);
        verify(paymentRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(Payment.class));
        verify(eventPublisher, org.mockito.Mockito.never())
                .paymentSettled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sagaRefund_failedPayment_throws() {
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment(Payment.STATUS_FAILED)));
        assertThatThrownBy(() -> service.sagaRefund(1L, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be refunded");
    }

    @Test
    void sagaRefund_unknownPayment_throwsNotFound() {
        when(paymentRepository.findByIdWithLock(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sagaRefund(9L, "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
