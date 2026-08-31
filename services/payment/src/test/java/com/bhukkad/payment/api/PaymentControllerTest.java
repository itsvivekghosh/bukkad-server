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
    @InjectMocks private PaymentController controller;

    @Test
    void pay_delegatesToProcessPayment() {
        Payment payment = new Payment();
        payment.setId(1L);
        when(paymentService.processPayment(10L, 2L, new BigDecimal("100.00"), "idem-1"))
                .thenReturn(payment);

        PaymentController.PaymentRequest request =
                new PaymentController.PaymentRequest(10L, 2L, new BigDecimal("100.00"), "INR");

        Payment result = controller.pay(request, "idem-1");

        assertThat(result.getId()).isEqualTo(1L);
        verify(paymentService).processPayment(10L, 2L, new BigDecimal("100.00"), "idem-1");
    }

    @Test
    void get_delegatesToGetPayment() {
        Payment payment = new Payment();
        payment.setId(5L);
        when(paymentService.getPayment(5L)).thenReturn(payment);

        assertThat(controller.get(5L).getId()).isEqualTo(5L);
    }
}
