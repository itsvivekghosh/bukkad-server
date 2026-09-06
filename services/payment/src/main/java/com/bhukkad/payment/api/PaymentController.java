package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.mapper.PaymentMapper;
import com.bhukkad.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentMapper paymentMapper;

    @PostMapping
    public PaymentResponse pay(@Valid @RequestBody PaymentRequest request,
                               @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Payment payment = paymentService.processPayment(
                request.getOrderId(),
                request.getCustomerId(),
                request.getAmount(),
                request.getPaymentMethod(),
                idempotencyKey);
        return paymentMapper.toPaymentResponse(payment);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable Long paymentId) {
        Payment payment = paymentService.getPayment(paymentId);
        return paymentMapper.toPaymentResponse(payment);
    }
}