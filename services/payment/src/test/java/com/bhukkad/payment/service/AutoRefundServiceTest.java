package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
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
}
