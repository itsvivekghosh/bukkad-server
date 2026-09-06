package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.domain.WalletTransaction;
import com.bhukkad.payment.mapper.PaymentMapper;
import com.bhukkad.payment.service.PaymentService;
import com.bhukkad.payment.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final PaymentService paymentService;
    private final PaymentMapper paymentMapper;

    @GetMapping("/customers/{customerId}/wallet")
    public WalletResponse balance(@PathVariable Long customerId) {
        WalletBalance balance = walletService.balance(customerId);
        return paymentMapper.toWalletResponse(balance);
    }

    @PostMapping("/customers/{customerId}/wallet/top-up")
    public WalletResponse topUp(@PathVariable Long customerId,
                                @Valid @RequestBody WalletTopUpRequest request,
                                @RequestHeader("Idempotency-Key") String idempotencyKey) {
        paymentService.processPayment(0L, customerId, request.getAmount(),
                Payment.METHOD_WALLET, idempotencyKey);
        WalletBalance balance = walletService.balance(customerId);
        return paymentMapper.toWalletResponse(balance);
    }

    @GetMapping("/wallet/transactions")
    public Map<String, Object> transactions(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<WalletTransaction> items = walletService.transactionsPage(customerId, page, size);
        return Map.of(
                "items", items.stream().map(paymentMapper::toWalletTransactionResponse).toList(),
                "page", Math.max(page, 0),
                "size", size,
                "hasNext", items.size() >= size);
    }

    @GetMapping("/wallet/transactions/cursor")
    public Map<String, Object> transactionsByCursor(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int size) {
        WalletService.CursorPage result = walletService.transactionsByCursor(customerId, cursor, size);
        return Map.of(
                "items", result.items().stream().map(paymentMapper::toWalletTransactionResponse).toList(),
                "nextCursor", result.nextCursor() == null ? "" : result.nextCursor(),
                "hasNext", result.hasNext(),
                "size", size);
    }

    @PostMapping("/internal/wallet/credit")
    public WalletResponse credit(@RequestBody Map<String, Object> body) {
        Long customerId = ((Number) body.get("customerId")).longValue();
        double amount = ((Number) body.get("amount")).doubleValue();
        String type = body.getOrDefault("type", "CREDIT").toString();
        String reference = body.getOrDefault("reference", "").toString();
        walletService.credit(customerId, BigDecimal.valueOf(amount), type + ":" + reference);
        return paymentMapper.toWalletResponse(walletService.balance(customerId));
    }

    @PostMapping("/internal/wallet/debit")
    public WalletResponse debit(@RequestBody Map<String, Object> body) {
        Long customerId = ((Number) body.get("customerId")).longValue();
        double amount = ((Number) body.get("amount")).doubleValue();
        String type = body.getOrDefault("type", "DEBIT").toString();
        String reference = body.getOrDefault("reference", "").toString();
        walletService.debit(customerId, BigDecimal.valueOf(amount), type + ":" + reference);
        return paymentMapper.toWalletResponse(walletService.balance(customerId));
    }
}