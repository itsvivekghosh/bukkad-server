package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.AgentCodWallet;
import com.bhukkad.payment.domain.AgentCodWalletRepository;
import com.bhukkad.payment.domain.CodWalletLedger;
import com.bhukkad.payment.domain.CodWalletLedgerRepository;
import com.bhukkad.payment.domain.RiderEarning;
import com.bhukkad.payment.domain.RiderEarningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * COD rider money operations (audit V-02 finish): wallet balance reads,
 * credit/debit, and rider earnings.
 *
 * <p>Transactions live HERE, not on the controller — the delegate pattern of
 * {@code WalletService}. Every balance mutation holds the pessimistic
 * {@code findByAgentIdForUpdate} lock and appends one
 * {@link CodWalletLedger} row inside the SAME transaction, so the ledger can
 * never disagree with the balance it audits.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodWalletService {

    /** Guards against fat-fingered or fabricated single-earning amounts. */
    static final BigDecimal MAX_EARNING_AMOUNT = new BigDecimal("10000.00");

    private final AgentCodWalletRepository codWalletRepository;
    private final RiderEarningRepository earningRepository;
    private final CodWalletLedgerRepository ledgerRepository;

    /**
     * Read-only GET backing: a missing wallet reads as a zero balance
     * (no INSERT-on-GET race).
     */
    @Transactional(readOnly = true)
    public BigDecimal codWalletBalance(Long agentId) {
        return codWalletRepository.findByAgentId(agentId)
                .map(AgentCodWallet::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional
    public AgentCodWallet credit(Long agentId, BigDecimal amount) {
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
        appendLedger(agentId, "CREDIT", amount, wallet.getBalance());

        log.info("Credited COD wallet: agentId={}, amount={}, newBalance={}", agentId, amount, wallet.getBalance());
        return wallet;
    }

    @Transactional
    public AgentCodWallet debit(Long agentId, BigDecimal amount) {
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
        appendLedger(agentId, "DEBIT", amount, wallet.getBalance());

        log.info("Debited COD wallet: agentId={}, amount={}, newBalance={}", agentId, amount, wallet.getBalance());
        return wallet;
    }

    /**
     * One ledger row per committed balance mutation, written inside the
     * caller's transaction. {@code balanceAfter} is taken from the persisted
     * entity — never recomputed from a possibly stale in-memory read (the
     * WalletService M-1 discipline).
     */
    private void appendLedger(Long agentId, String type, BigDecimal amount, BigDecimal balanceAfter) {
        CodWalletLedger entry = new CodWalletLedger();
        entry.setAgentId(agentId);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        ledgerRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<RiderEarning> earnings(Long agentId) {
        return earningRepository.findByAgentId(agentId);
    }

    /**
     * Records a rider earning. Idempotent by (agentId, orderId): a retry,
     * replay, or duplicate dispatch must not mint a second payable earning,
     * so a duplicate completes with an EMPTY result.
     */
    @Transactional
    public Optional<RiderEarning> recordEarning(Long agentId, Long orderId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("Earning amount must be positive");
        }
        if (amount.compareTo(MAX_EARNING_AMOUNT) > 0) {
            throw new BusinessException("Earning amount exceeds the per-delivery limit");
        }
        // Idempotent by (agentId, orderId): a retry, replay, or duplicate
        // dispatch previously minted a second payable earning.
        if (earningRepository.countByAgentIdAndOrderId(agentId, orderId) > 0) {
            return Optional.empty();
        }

        RiderEarning earning = new RiderEarning();
        earning.setAgentId(agentId);
        earning.setOrderId(orderId);
        earning.setAmount(amount);
        earning.setStatus("EARNED");
        earning = earningRepository.save(earning);

        log.info("Recorded rider earning: agentId={}, orderId={}, amount={}", agentId, orderId, amount);
        return Optional.of(earning);
    }

    @Transactional
    public void markEarningPaid(Long earningId) {
        // Guarded transition: EARNED → PAID only. A replay of this call used
        // to re-flip WITHHELD rows to PAID with no approval trail.
        int updated = earningRepository.markPaidIfEarned(earningId);
        if (updated == 0) {
            throw new BusinessException(
                    "Earning " + earningId + " is not in an payable (EARNED) state");
        }

        log.info("Marked earning as paid: earningId={}", earningId);
    }
}
