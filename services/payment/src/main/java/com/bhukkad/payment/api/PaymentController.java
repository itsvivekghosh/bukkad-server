package com.bhukkad.payment.api;

import com.bhukkad.payment.service.PaymentService;
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

/**
 * Payment service API — {@code /api/v1/payments}. The {@code Idempotency-Key}
 * header is the authoritative guard (plan §7: "idempotency-key required").
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    public record PaymentRequest(
            @Positive Long orderId,
            @Positive Long customerId,
            @Positive BigDecimal amount,
            @NotBlank String currency
    ) {}

    @PostMapping
    public com.bhukkad.payment.domain.Payment pay(@RequestBody PaymentRequest request,
                                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return paymentService.processPayment(request.orderId(), request.customerId(),
                request.amount(), idempotencyKey);
    }

    @GetMapping("/{paymentId}")
    public com.bhukkad.payment.domain.Payment get(@PathVariable Long paymentId) {
        return paymentService.getPayment(paymentId);
    }
}