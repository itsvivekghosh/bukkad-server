package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.AgentCodWallet;
import com.bhukkad.payment.domain.RiderEarning;
import com.bhukkad.payment.service.CodWalletService;
import com.bhukkad.payment.service.RiderEarningService;
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
 * <p>Audit V-02 finish: pure HTTP mapping — transaction demarcation, money
 * guards and the append-only COD ledger live in {@link CodWalletService} and
 * {@link RiderEarningService}. Request paths, parameters and response shapes
 * are contract-frozen ({@code orderId} is an optional attribution added for
 * the ledger; absent it, calls behave exactly as before).</p>
 */
@RestController
@RequestMapping("/api/v1/internal/delivery")
@RequiredArgsConstructor
public class DeliveryPaymentController {

    private final CodWalletService codWalletService;
    private final RiderEarningService earningService;

    // ============= COD Wallet Operations =============

    /** Read-only GET: a missing wallet reads as a zero balance (no INSERT-on-GET race). */
    @GetMapping("/cod-wallet/{agentId}")
    public ResponseEntity<Map<String, Object>> getCodWallet(@PathVariable Long agentId) {
        return ResponseEntity.ok(Map.of(
                "agentId", agentId,
                "balance", codWalletService.balance(agentId)
        ));
    }

    @PostMapping("/cod-wallet/{agentId}/credit")
    public ResponseEntity<Map<String, Object>> creditCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) Long orderId) {
        AgentCodWallet wallet = codWalletService.credit(agentId, amount, orderId);

        return ResponseEntity.ok(Map.of(
                "agentId", wallet.getAgentId(),
                "balance", wallet.getBalance(),
                "credited", amount
        ));
    }

    @PostMapping("/cod-wallet/{agentId}/debit")
    public ResponseEntity<Map<String, Object>> debitCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) Long orderId) {
        AgentCodWallet wallet = codWalletService.debit(agentId, amount, orderId);

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
        RiderEarningService.EarningResult result = earningService.record(agentId, orderId, amount);
        if (result.duplicate()) {
            return ResponseEntity.ok(Map.of(
                    "agentId", agentId,
                    "orderId", orderId,
                    "duplicate", true
            ));
        }

        RiderEarning earning = result.earning();
        return ResponseEntity.ok(Map.of(
                "id", earning.getId(),
                "agentId", earning.getAgentId(),
                "orderId", earning.getOrderId(),
                "amount", earning.getAmount(),
                "status", earning.getStatus(),
                "createdAt", earning.getCreatedAt()
        ));
    }

    @GetMapping("/earnings/{agentId}")
    public ResponseEntity<List<Map<String, Object>>> getEarnings(@PathVariable Long agentId) {
        List<RiderEarning> earnings = earningService.listByAgent(agentId);

        List<Map<String, Object>> response = earnings.stream()
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
        earningService.markPaid(earningId);

        return ResponseEntity.ok(Map.of(
                "id", earningId,
                "status", "PAID"
        ));
    }
}
