package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.AgentCodWallet;
import com.bhukkad.payment.domain.RiderEarning;
import com.bhukkad.payment.service.CodWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Internal delivery ↔ payment surface (rider COD wallets + earnings).
 *
 * <p>This controller sits under {@code /api/v1/internal/**}: the shared
 * {@code ServiceJwtAuthFilter} (platform-lib) REQUIRES a valid service token
 * for these paths, so only mesh services may move rider money. The filter
 * previously never existed as a bean, leaving these endpoints reachable with
 * any ordinary user JWT.</p>
 *
 * <p>Thin HTTP mapping only (audit V-02 finish): all money logic and the
 * transaction boundaries live in {@link CodWalletService}, which also writes
 * the append-only {@code cod_wallet_ledger} audit trail inside the same
 * transaction as every balance mutation.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/delivery")
@RequiredArgsConstructor
public class DeliveryPaymentController {

    private final CodWalletService codWalletService;

    // ============= COD Wallet Operations =============

    @GetMapping("/cod-wallet/{agentId}")
    public ResponseEntity<Map<String, Object>> getCodWallet(@PathVariable Long agentId) {
        BigDecimal balance = codWalletService.codWalletBalance(agentId);
        return ResponseEntity.ok(Map.of(
                "agentId", agentId,
                "balance", balance
        ));
    }

    @PostMapping("/cod-wallet/{agentId}/credit")
    public ResponseEntity<Map<String, Object>> creditCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount) {
        AgentCodWallet wallet = codWalletService.credit(agentId, amount);
        return ResponseEntity.ok(Map.of(
                "agentId", wallet.getAgentId(),
                "balance", wallet.getBalance(),
                "credited", amount
        ));
    }

    @PostMapping("/cod-wallet/{agentId}/debit")
    public ResponseEntity<Map<String, Object>> debitCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount) {
        AgentCodWallet wallet = codWalletService.debit(agentId, amount);
        return ResponseEntity.ok(Map.of(
                "agentId", wallet.getAgentId(),
                "balance", wallet.getBalance(),
                "debited", amount
        ));
    }

    // ============= Rider Earnings Operations =============

    @PostMapping("/earnings/record")
    public ResponseEntity<Map<String, Object>> recordEarning(
            @RequestParam Long agentId,
            @RequestParam Long orderId,
            @RequestParam BigDecimal amount) {
        return codWalletService.recordEarning(agentId, orderId, amount)
                .<ResponseEntity<Map<String, Object>>>map(earning -> ResponseEntity.ok(Map.of(
                        "id", earning.getId(),
                        "agentId", earning.getAgentId(),
                        "orderId", earning.getOrderId(),
                        "amount", earning.getAmount(),
                        "status", earning.getStatus(),
                        "createdAt", earning.getCreatedAt()
                )))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "agentId", agentId,
                        "orderId", orderId,
                        "duplicate", true
                )));
    }

    @GetMapping("/earnings/{agentId}")
    public ResponseEntity<List<Map<String, Object>>> getEarnings(@PathVariable Long agentId) {
        List<Map<String, Object>> response = codWalletService.earnings(agentId).stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getId(),
                        "agentId", e.getAgentId(),
                        "orderId", e.getOrderId(),
                        "amount", e.getAmount(),
                        "status", e.getStatus(),
                        "createdAt", e.getCreatedAt(),
                        "paidAt", e.getPaidAt() != null ? e.getPaidAt() : ""
                ))
                .toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/earnings/{earningId}/mark-paid")
    public ResponseEntity<Map<String, Object>> markEarningPaid(@PathVariable Long earningId) {
        codWalletService.markEarningPaid(earningId);
        return ResponseEntity.ok(Map.of(
                "id", earningId,
                "status", "PAID"
        ));
    }
}
