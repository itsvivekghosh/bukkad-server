package com.bhukkad.payment.api;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.domain.WalletTransaction;
import com.bhukkad.payment.mapper.PaymentMapper;
import com.bhukkad.payment.service.PaymentService;
import com.bhukkad.payment.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public WalletResponse balance(@AuthenticationPrincipal TokenPrincipal principal,
                                  @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        WalletBalance balance = walletService.balance(customerId);
        return paymentMapper.toWalletResponse(balance);
    }

    @PostMapping("/customers/{customerId}/wallet/top-up")
    public WalletResponse topUp(@AuthenticationPrincipal TokenPrincipal principal,
                                @PathVariable Long customerId,
                                @Valid @RequestBody WalletTopUpRequest request,
                                @RequestHeader("Idempotency-Key") String idempotencyKey) {
        // Only the wallet owner (or an admin) may top it up.
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        paymentService.processPayment(0L, customerId, request.getAmount(),
                Payment.METHOD_WALLET, idempotencyKey);
        WalletBalance balance = walletService.balance(customerId);
        return paymentMapper.toWalletResponse(balance);
    }

    @GetMapping("/wallet/transactions")
    public Map<String, Object> transactions(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // Identity from the JWT: the previous X-Customer-Id header let any
        // caller enumerate any customer's transaction ledger.
        Long customerId = requireCustomerId(principal);
        List<WalletTransaction> items = walletService.transactionsPage(customerId, page, size);
        int safeSize = Math.min(Math.max(size, 1), com.bhukkad.common.util.PaginationUtils.MAX_PAGE_SIZE);
        return Map.of(
                "items", items.stream().map(paymentMapper::toWalletTransactionResponse).toList(),
                "page", Math.max(page, 0),
                "size", safeSize,
                "hasNext", items.size() >= safeSize);
    }

    @GetMapping("/wallet/transactions/cursor")
    public Map<String, Object> transactionsByCursor(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int size) {
        Long customerId = requireCustomerId(principal);
        WalletService.CursorPage result = walletService.transactionsByCursor(customerId, cursor, size);
        return Map.of(
                "items", result.items().stream().map(paymentMapper::toWalletTransactionResponse).toList(),
                "nextCursor", result.nextCursor() == null ? "" : result.nextCursor(),
                "hasNext", result.hasNext(),
                "size", result.items().size());
    }

    /**
     * Internal service-to-service wallet credit. Validated DTO instead of an
     * untyped map (missing fields previously NPE'd into a 500, and the
     * amount was a raw double).
     */
    public record InternalWalletRequest(
            @jakarta.validation.constraints.NotNull Long customerId,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive BigDecimal amount,
            String reference) {}

    @PostMapping("/internal/wallet/credit")
    public WalletResponse credit(@Valid @RequestBody InternalWalletRequest request) {
        // M-1: report/anchor on the balance the atomic update actually
        // persisted (the service returns it); re-reading the wallet here
        // could surface a concurrent transaction's value as this one's.
        WalletBalance wallet = walletService.credit(request.customerId(), request.amount(),
                "CREDIT:" + (request.reference() == null ? "" : request.reference()));
        return paymentMapper.toWalletResponse(wallet);
    }

    @PostMapping("/internal/wallet/debit")
    public WalletResponse debit(@Valid @RequestBody InternalWalletRequest request) {
        WalletBalance wallet = walletService.debit(request.customerId(), request.amount(),
                "DEBIT:" + (request.reference() == null ? "" : request.reference()));
        return paymentMapper.toWalletResponse(wallet);
    }

    private static Long requireCustomerId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }

    // ------------------------------------------------------------------
    // Monolith-parity self surface: /customers/wallet/** without a path id.
    // The acting customer is always the JWT subject (IDOR-safe).
    // ------------------------------------------------------------------

    @GetMapping("/customers/wallet/balance")
    public WalletResponse selfBalance(@AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = requireCustomerId(principal);
        return paymentMapper.toWalletResponse(walletService.balance(customerId));
    }

    /** Direct credit from a completed external payment (self service). */
    @PostMapping("/customers/wallet/add-money")
    public WalletResponse selfAddMoney(@AuthenticationPrincipal TokenPrincipal principal,
                                       @RequestParam BigDecimal amount,
                                       @RequestHeader(value = "Idempotency-Key", required = false)
                                       String idempotencyKey) {
        Long customerId = requireCustomerId(principal);
        if (amount == null || amount.signum() <= 0) {
            throw new com.bhukkad.common.error.BusinessException(
                    "amount must be positive");
        }
        WalletBalance wallet = walletService.credit(customerId, amount,
                "ADD_MONEY:" + (idempotencyKey == null ? "" : idempotencyKey));
        return paymentMapper.toWalletResponse(wallet);
    }

    /**
     * Razorpay-style wallet top-up. The dev build has no payment-gateway
     * credentials configured, so the top-up is simulated as a direct credit
     * (same ledger, same idempotency reference shape).
     */
    @PostMapping("/customers/wallet/top-up")
    public WalletResponse selfTopUp(@AuthenticationPrincipal TokenPrincipal principal,
                                    @RequestParam BigDecimal amount) {
        return selfAddMoney(principal, amount, "SELF-TOPUP");
    }

    @GetMapping("/customers/wallet/transactions")
    public Map<String, Object> selfTransactions(@AuthenticationPrincipal TokenPrincipal principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return transactions(principal, page, size);
    }

    @GetMapping("/customers/wallet/transactions/cursor")
    public Map<String, Object> selfTransactionsByCursor(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int size) {
        return transactionsByCursor(principal, cursor, size);
    }
}
