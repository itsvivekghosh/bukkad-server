package com.bhukkad.payment.api;

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

@Slf4j
@RestController
@RequestMapping("/api/v1/internal/delivery")
@RequiredArgsConstructor
public class DeliveryPaymentController {

    private final AgentCodWalletRepository codWalletRepository;
    private final RiderEarningRepository earningRepository;

    // ============= COD Wallet Operations =============

    @GetMapping("/cod-wallet/{agentId}")
    public ResponseEntity<Map<String, Object>> getCodWallet(@PathVariable Long agentId) {
        AgentCodWallet wallet = codWalletRepository.findByAgentId(agentId)
                .orElseGet(() -> {
                    AgentCodWallet newWallet = new AgentCodWallet();
                    newWallet.setAgentId(agentId);
                    newWallet.setBalance(BigDecimal.ZERO);
                    return codWalletRepository.save(newWallet);
                });

        return ResponseEntity.ok(Map.of(
                "agentId", wallet.getAgentId(),
                "balance", wallet.getBalance(),
                "updatedAt", wallet.getUpdatedAt()
        ));
    }

    @PostMapping("/cod-wallet/{agentId}/credit")
    @Transactional
    public ResponseEntity<Map<String, Object>> creditCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount) {

        AgentCodWallet wallet = codWalletRepository.findByAgentId(agentId)
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

        AgentCodWallet wallet = codWalletRepository.findByAgentId(agentId)
                .orElseThrow(() -> new IllegalStateException("COD wallet not found for agent " + agentId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient COD wallet balance");
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
        RiderEarning earning = earningRepository.findById(earningId)
                .orElseThrow(() -> new IllegalStateException("Earning not found: " + earningId));

        earning.setStatus("PAID");
        earning.setPaidAt(java.time.LocalDateTime.now());
        earning = earningRepository.save(earning);

        log.info("Marked earning as paid: earningId={}", earningId);

        return ResponseEntity.ok(Map.of(
                "id", earning.getId(),
                "status", earning.getStatus(),
                "paidAt", earning.getPaidAt()
        ));
    }
}
