package com.bhukkad.payment.api;

import com.bhukkad.payment.service.InternalPaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Internal payment surface for the order saga (batch A contract).
 *
 * <p>Lives under {@code /api/v1/internal/**}: the platform-lib
 * {@code ServiceJwtAuthFilter} grants ROLE_SERVICE only for a valid
 * {@code X-Service-Token} and rejects token-less internal requests when
 * {@code SERVICE_JWT_SECRET} is configured; the {@code @PreAuthorize} below
 * additionally admits operators carrying ROLE_ADMIN.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final InternalPaymentService internalPaymentService;

    public record ChargeRequest(
            @NotNull Long orderId,
            @NotNull Long customerId,
            // Jackson coerces both "100.00" (string) and 100 (number) here.
            @NotNull @Positive BigDecimal amount,
            @NotBlank String paymentMethod,
            @NotBlank String reference) {
    }

    public record RefundRequest(String reason) {
    }

    public record ChargeResponse(Long paymentId, String status) {
    }

    public record RefundResponse(Long paymentId, String status) {
    }

    @PostMapping("/charge")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ChargeResponse charge(@Valid @RequestBody ChargeRequest request) {
        InternalPaymentService.SagaOutcome outcome = internalPaymentService.charge(
                new InternalPaymentService.ChargeCommand(
                        request.orderId(), request.customerId(), request.amount(),
                        request.paymentMethod(), request.reference()));
        return new ChargeResponse(outcome.paymentId(), outcome.status());
    }

    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public RefundResponse refund(@PathVariable Long paymentId,
                                 @RequestBody(required = false) RefundRequest request) {
        InternalPaymentService.SagaOutcome outcome = internalPaymentService.refund(
                paymentId, request == null ? null : request.reason());
        return new RefundResponse(outcome.paymentId(), outcome.status());
    }
}
