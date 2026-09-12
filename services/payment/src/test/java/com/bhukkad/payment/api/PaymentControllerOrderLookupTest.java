package com.bhukkad.payment.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.payment.api.dto.response.PaymentResponse;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.mapper.PaymentMapper;
import com.bhukkad.payment.domain.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerOrderLookupTest {

    @Mock private PaymentService paymentService;
    @Mock private PaymentMapper paymentMapper;
    @InjectMocks private PaymentController controller;

    private Payment paymentOwnedBy(long customerId) {
        Payment payment = new Payment();
        payment.setId(100L);
        payment.setCustomerId(customerId);
        return payment;
    }

    @Test
    void byOrder_ownerGetsPayment() {
        Payment payment = paymentOwnedBy(2L);
        when(paymentService.getPaymentByOrder(77L)).thenReturn(payment);
        when(paymentMapper.toPaymentResponse(payment))
                .thenReturn(PaymentResponse.builder().id(100L).build());

        PaymentResponse response = controller.byOrder(
                new TokenPrincipal(2L, "c@x.io", "CUSTOMER"), 77L);

        assertThat(response.getId()).isEqualTo(100L);
    }

    @Test
    void byOrder_foreignCustomerDenied() {
        when(paymentService.getPaymentByOrder(77L)).thenReturn(paymentOwnedBy(2L));

        assertThatThrownBy(() -> controller.byOrder(
                new TokenPrincipal(3L, "other@x.io", "CUSTOMER"), 77L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void byOrder_adminScopeAllowed() {
        Payment payment = paymentOwnedBy(2L);
        when(paymentService.getPaymentByOrder(77L)).thenReturn(payment);
        when(paymentMapper.toPaymentResponse(payment))
                .thenReturn(PaymentResponse.builder().id(100L).build());

        assertThat(controller.byOrder(
                new TokenPrincipal(9L, "admin@x.io", "ADMIN"), 77L).getId()).isEqualTo(100L);
    }
}
