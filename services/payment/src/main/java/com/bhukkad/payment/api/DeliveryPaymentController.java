package com.bhukkad.payment.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.AgentCodWallet;
import com.bhukkad.payment.domain.AgentCodWalletRepository;
import com.bhukkad.payment.domain.RiderEarning;
import com.bhukkad.payment.domain.RiderEarningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/delivery")
@RequiredArgsConstructor
public class DeliveryPaymentController {

    /** Guards against fat-fingered or fabricated single-earning amounts. */
    private static final BigDecimal MAX_EARNING_AMOUNT = new BigDecimal("10000.00");

    private final AgentCodWalletRepository codWalletRepository;
    private final RiderEarningRepository earningRepository;

    // ============= COD Wallet Operations =============

    /** Read-only GET: a missing wallet reads as a zero balance (no INSERT-on-GET race). */
    @GetMapping("/cod-wallet/{agentId}")
    public ResponseEntity<Map<String, Object>> getCodWallet(@PathVariable Long agentId) {
        BigDecimal balance = codWalletRepository.findByAgentId(agentId)
                .map(AgentCodWallet::getBalance)
                .orElse(BigDecimal.ZERO);
        return ResponseEntity.ok(Map.of(
                "agentId", agentId,
                "balance", balance
        ));
    }

    @PostMapping("/cod-wallet/{agentId}/credit")
    @Transactional
    public ResponseEntity<Map<String, Object>> creditCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount) {
        if (amount.signum() <= 0) {
            // A negative "credit" was an unguarded debit bypassing the
            // balance check on the debit path.
            throw new BusinessException("Credit amount must be positive");
        }
        AgentCodWallet wallet = codWalletRepository.findByAgentIdForUpdate(agentId)
                .orElseGet(() -> {
                    AgentCodWallet newWallet = new AgentCodWallet();
                    newWallet.setAgentId(agentId);
                    newWallet.setBalance(BigDecimal.ZERO);
                    return codWalletRepository.save(newWallet);
                });

        wallet.setBalance(wallet.getBalance().add(amount));
        wallet = codWalletRepository.save(wallet);

        log.info("Credited COD wallet: agentId={}, amount={}, newBalance={}", agentId, amount, wallet.getBalance());

        return ResponseEntity.ok(Map.of(
                "agentId", wallet.getAgentId(),
                "balance", wallet.getBalance(),
                "credited", amount
        ));
    }

    @PostMapping("/cod-wallet/{agentId}/debit")
    @Transactional
    public ResponseEntity<Map<String, Object>> debitCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("Debit amount must be positive");
        }
        // Pessimistic lock closes the check-then-act overdraw race.
        AgentCodWallet wallet = codWalletRepository.findByAgentIdForUpdate(agentId)
                .orElseThrow(() -> new BusinessException("COD wallet not found for agent " + agentId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("Insufficient COD wallet balance");
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        wallet = codWalletRepository.save(wallet);

        log.info("Debited COD wallet: agentId={}, amount={}, newBalance={}", agentId, amount, wallet.getBalance());

        return ResponseEntity.ok(Map.of(
                "agentId", wallet.getAgentId(),
                "balance", wallet.getBalance(),
                "debited", amount
        ));
    }

    // ============= Rider Earnings Operations =============

    @PostMapping("/earnings/record")
    @Transactional
    public ResponseEntity<Map<String, Object>> recordEarning(
            @RequestParam Long agentId,
            @RequestParam Long orderId,
            @RequestParam BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("Earning amount must be positive");
        }
        if (amount.compareTo(MAX_EARNING_AMOUNT) > 0) {
            throw new BusinessException("Earning amount exceeds the per-delivery limit");
        }
        // Idempotent by (agentId, orderId): a retry, replay, or duplicate
        // dispatch previously minted a second payable earning.
        if (earningRepository.countByAgentIdAndOrderId(agentId, orderId) > 0) {
            return ResponseEntity.ok(Map.of(
                    "agentId", agentId,
                    "orderId", orderId,
                    "duplicate", true
            ));
        }

        RiderEarning earning = new RiderEarning();
        earning.setAgentId(agentId);
        earning.setOrderId(orderId);
        earning.setAmount(amount);
        earning.setStatus("EARNED");
        earning = earningRepository.save(earning);

        log.info("Recorded rider earning: agentId={}, orderId={}, amount={}", agentId, orderId, amount);

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
        List<RiderEarning> earnings = earningRepository.findByAgentId(agentId);

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
    @Transactional
    public ResponseEntity<Map<String, Object>> markEarningPaid(@PathVariable Long earningId) {
        // Guarded transition: EARNED → PAID only. A replay of this call used
        // to re-flip WITHHELD rows to PAID with no approval trail.
        int updated = earningRepository.markPaidIfEarned(earningId);
        if (updated == 0) {
            throw new BusinessException(
                    "Earning " + earningId + " is not in an payable (EARNED) state");
        }

        log.info("Marked earning as paid: earningId={}", earningId);

        return ResponseEntity.ok(Map.of(
                "id", earningId,
                "status", "PAID"
        ));
    }
}
