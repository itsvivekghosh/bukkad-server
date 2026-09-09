package com.bhukkad.payment.api;

import com.bhukkad.payment.service.InternalPaymentService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
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
class InternalPaymentControllerTest {

    @Mock private InternalPaymentService internalPaymentService;
    @InjectMocks private InternalPaymentController controller;

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void charge_mapsRequestToCommandAndEchoesSagaOutcome() {
        when(internalPaymentService.charge(new InternalPaymentService.ChargeCommand(
                55L, 7L, new BigDecimal("250.00"), "UPI", "ref-1")))
                .thenReturn(new InternalPaymentService.SagaOutcome(11L, "CHARGED"));

        InternalPaymentController.ChargeResponse response = controller.charge(
                new InternalPaymentController.ChargeRequest(
                        55L, 7L, new BigDecimal("250.00"), "UPI", "ref-1"));

        assertThat(response.paymentId()).isEqualTo(11L);
        assertThat(response.status()).isEqualTo("CHARGED");
        verify(internalPaymentService).charge(new InternalPaymentService.ChargeCommand(
                55L, 7L, new BigDecimal("250.00"), "UPI", "ref-1"));
    }

    @Test
    void refund_toleratesEmptyBodyAndMapsOutcome() {
        when(internalPaymentService.refund(7L, null))
                .thenReturn(new InternalPaymentService.SagaOutcome(7L, "REFUNDED"));

        InternalPaymentController.RefundResponse response = controller.refund(7L, null);

        assertThat(response.paymentId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo("REFUNDED");
    }

    @Test
    void refund_passesReasonThrough() {
        when(internalPaymentService.refund(7L, "order cancelled"))
                .thenReturn(new InternalPaymentService.SagaOutcome(7L, "REFUNDED"));

        controller.refund(7L, new InternalPaymentController.RefundRequest("order cancelled"));

        verify(internalPaymentService).refund(7L, "order cancelled");
    }

    @Test
    void chargeRequest_rejectsMissingFieldsAndNonPositiveAmount() {
        assertThat(validator.validate(new InternalPaymentController.ChargeRequest(
                null, null, null, null, null)))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("orderId", "customerId", "amount", "paymentMethod", "reference");

        assertThat(validator.validate(new InternalPaymentController.ChargeRequest(
                1L, 2L, new BigDecimal("-5.00"), "UPI", "ref"))).isNotEmpty();

        assertThat(validator.validate(new InternalPaymentController.ChargeRequest(
                1L, 2L, new BigDecimal("10.00"), "UPI", "ref"))).isEmpty();
    }
}
