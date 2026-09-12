package com.bhukkad.payment.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.mapper.PaymentMapper;
import com.bhukkad.payment.domain.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bhukkad.payment.api.dto.request.PaymentRequest;
import com.bhukkad.payment.api.dto.response.PaymentResponse;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentMapper paymentMapper;

    @PostMapping
    public PaymentResponse pay(@AuthenticationPrincipal TokenPrincipal principal,
                               @Valid @RequestBody PaymentRequest request,
                               @RequestHeader("Idempotency-Key") String idempotencyKey) {
        // The payer is the authenticated subject — a body customerId could
        // otherwise push the payment (and any wallet movement) onto a victim.
        PrincipalGuard.requireAuthenticated(principal);
        Payment payment = paymentService.processPayment(
                request.getOrderId(),
                principal.userId(),
                request.getAmount(),
                request.getPaymentMethod(),
                idempotencyKey);
        return paymentMapper.toPaymentResponse(payment);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@AuthenticationPrincipal TokenPrincipal principal,
                               @PathVariable Long paymentId) {
        Payment payment = paymentService.getPayment(paymentId);
        PrincipalGuard.requireSelfOrAdmin(principal, payment.getCustomerId());
        return paymentMapper.toPaymentResponse(payment);
    }

    /** The payment attached to an order (self-or-admin; 404 when unpaid). */
    @GetMapping("/orders/{orderId}")
    public PaymentResponse byOrder(@AuthenticationPrincipal TokenPrincipal principal,
                                   @PathVariable Long orderId) {
        Payment payment = paymentService.getPaymentByOrder(orderId);
        PrincipalGuard.requireSelfOrAdmin(principal, payment.getCustomerId());
        return paymentMapper.toPaymentResponse(payment);
    }
}