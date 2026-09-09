package com.bhukkad.payment.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock private PaymentService paymentService;
    @Mock private com.bhukkad.payment.mapper.PaymentMapper paymentMapper;
    @InjectMocks private PaymentController controller;

    private TokenPrincipal principal(Long userId) {
        return new TokenPrincipal(userId, "u@example.com", "CUSTOMER");
    }

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
        PaymentResponse response = PaymentResponse.builder()
                .id(1L).orderId(10L).customerId(2L)
                .amount(new BigDecimal("100.00")).status(Payment.STATUS_SETTLED).build();
        when(paymentMapper.toPaymentResponse(payment)).thenReturn(response);

        PaymentRequest request = new PaymentRequest();
        request.setOrderId(10L);
        request.setCustomerId(2L);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("INR");
        request.setPaymentMethod("UPI");

        // The body customerId is IGNORED; payer = JWT subject (IDOR guard).
        PaymentResponse result = controller.pay(principal(2L), request, "idem-1");

        assertThat(result.getId()).isEqualTo(1L);
        verify(paymentService).processPayment(10L, 2L, new BigDecimal("100.00"), "UPI", "idem-1");
    }

    @Test
    void pay_rejectsUnauthenticated() {
        PaymentRequest request = new PaymentRequest();
        request.setOrderId(10L);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("INR");
        request.setPaymentMethod("UPI");

        assertThatThrownBy(() -> controller.pay(null, request, "idem-2"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void get_delegatesToGetPayment() {
        Payment payment = new Payment();
        payment.setId(5L);
        payment.setCustomerId(2L);
        when(paymentService.getPayment(5L)).thenReturn(payment);
        PaymentResponse response = PaymentResponse.builder().id(5L).build();
        when(paymentMapper.toPaymentResponse(payment)).thenReturn(response);

        assertThat(controller.get(principal(2L), 5L).getId()).isEqualTo(5L);
    }

    @Test
    void get_otherCustomersPayment_throws() {
        Payment payment = new Payment();
        payment.setId(5L);
        payment.setCustomerId(77L);
        when(paymentService.getPayment(5L)).thenReturn(payment);

        assertThatThrownBy(() -> controller.get(principal(2L), 5L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
