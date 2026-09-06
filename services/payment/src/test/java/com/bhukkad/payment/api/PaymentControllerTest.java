package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock private PaymentService paymentService;
    @Mock private com.bhukkad.payment.mapper.PaymentMapper paymentMapper;
    @InjectMocks private PaymentController controller;

    @Test
    void pay_delegatesToProcessPayment() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrderId(10L);
        payment.setCustomerId(2L);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setStatus(Payment.STATUS_SETTLED);
        when(paymentService.processPayment(10L, 2L, new BigDecimal("100.00"), "UPI", "idem-1"))
                .thenReturn(payment);
        com.bhukkad.payment.api.PaymentResponse response =
                com.bhukkad.payment.api.PaymentResponse.builder()
                        .id(1L).orderId(10L).customerId(2L)
                        .amount(new BigDecimal("100.00")).status(Payment.STATUS_SETTLED).build();
        when(paymentMapper.toPaymentResponse(payment)).thenReturn(response);

        PaymentRequest request = new PaymentRequest();
        request.setOrderId(10L);
        request.setCustomerId(2L);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("INR");
        request.setPaymentMethod("UPI");

        com.bhukkad.payment.api.PaymentResponse result = controller.pay(request, "idem-1");

        assertThat(result.getId()).isEqualTo(1L);
        verify(paymentService).processPayment(10L, 2L, new BigDecimal("100.00"), "UPI", "idem-1");
    }

    @Test
    void get_delegatesToGetPayment() {
        Payment payment = new Payment();
        payment.setId(5L);
        when(paymentService.getPayment(5L)).thenReturn(payment);
        com.bhukkad.payment.api.PaymentResponse response =
                com.bhukkad.payment.api.PaymentResponse.builder().id(5L).build();
        when(paymentMapper.toPaymentResponse(payment)).thenReturn(response);

        assertThat(controller.get(5L).getId()).isEqualTo(5L);
    }
}
