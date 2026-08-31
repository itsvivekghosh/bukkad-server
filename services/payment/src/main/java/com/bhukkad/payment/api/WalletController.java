package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.service.PaymentService;
import com.bhukkad.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Wallet + payment surface for customers (port of monolith {@code WalletController}
 * + ingestion endpoints).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final PaymentService paymentService;

    @GetMapping("/customers/{customerId}/wallet")
    public WalletBalance balance(@PathVariable Long customerId) {
        return walletService.balance(customerId);
    }

    @PostMapping("/customers/{customerId}/wallet/top-up")
    public WalletBalance topUp(@PathVariable Long customerId, @RequestParam BigDecimal amount,
                               @RequestHeader("Idempotency-Key") String idempotencyKey) {
        // Top-up creates a payment, settles it, and credits the wallet.
        paymentService.processPayment(0L, customerId, amount, idempotencyKey);
        return walletService.balance(customerId);
    }

    @GetMapping("/customers/{customerId}/payments")
    public Object payments(@PathVariable Long customerId) {
        // Could use PaymentRepository.findByCustomerId if available
        return java.util.List.of();
    }
}