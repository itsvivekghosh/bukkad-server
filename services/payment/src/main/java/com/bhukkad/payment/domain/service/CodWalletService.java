package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.entity.AgentCodWallet;
import com.bhukkad.payment.domain.repository.AgentCodWalletRepository;
import com.bhukkad.payment.domain.entity.CodWalletLedger;
import com.bhukkad.payment.domain.repository.CodWalletLedgerRepository;
import com.bhukkad.payment.domain.entity.RiderEarning;
import com.bhukkad.payment.domain.repository.RiderEarningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Internal delivery money surface (audit V-02 finish): COD wallet movements
 * plus rider earning lifecycle, extracted from {@code DeliveryPaymentController}
 * so the transaction boundary lives on the service, not the controller.
 *
 * <p>Money-safety guards carried over unchanged:</p>
 * <ul>
 *   <li>pessimistic {@code FOR UPDATE} read serializes concurrent wallet
 *       mutations (check-then-act overdraw/lost-credit closed);</li>
 *   <li>positive-amount {@code signum()} guard on credit/debit/earnings;</li>
 *   <li>the wallet GET is read-only and inserts nothing (missing wallet reads
 *       as zero — no INSERT-on-GET race);</li>
 *   <li>earnings are idempotent by {@code (agentId, orderId)} and capped at
 *       {@link #MAX_EARNING_AMOUNT};</li>
 *   <li>every balance mutation appends an append-only
 *       {@link CodWalletLedger} row inside the SAME transaction (balance and
 *       evidence commit or roll back together).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodWalletService {

    /** Guards against fat-fingered or fabricated single-earning amounts. */
    static final BigDecimal MAX_EARNING_AMOUNT = new BigDecimal("10000.00");

    private final AgentCodWalletRepository codWalletRepository;
    private final CodWalletLedgerRepository codWalletLedgerRepository;
    private final RiderEarningRepository earningRepository;

    /**
     * Read-only balance probe: a missing wallet reads as a zero balance
     * (no INSERT-on-GET race).
     */
    @Transactional(readOnly = true)
    public BigDecimal balance(Long agentId) {
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
        appendLedger(agentId, "CREDIT", amount, wallet.getBalance(), null);

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
        appendLedger(agentId, "DEBIT", amount, wallet.getBalance(), null);

        log.info("Debited COD wallet: agentId={}, amount={}, newBalance={}", agentId, amount, wallet.getBalance());

        return wallet;
    }

    /**
     * Record a rider earning. Idempotent by {@code (agentId, orderId)}: a
     * retry, replay, or duplicate dispatch returns {@code duplicate=true} and
     * mints no second payable earning.
     */
    @Transactional
    public EarningRecord recordEarning(Long agentId, Long orderId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("Earning amount must be positive");
        }
        if (amount.compareTo(MAX_EARNING_AMOUNT) > 0) {
            throw new BusinessException("Earning amount exceeds the per-delivery limit");
        }
        // Idempotent by (agentId, orderId): a retry, replay, or duplicate
        // dispatch previously minted a second payable earning. The dedup check
        // shares the transaction with the insert so a replay cannot slip two
        // rows past the count (the V2 unique index stays the backstop).
        if (earningRepository.countByAgentIdAndOrderId(agentId, orderId) > 0) {
            return new EarningRecord(null, true);
        }

        RiderEarning earning = new RiderEarning();
        earning.setAgentId(agentId);
        earning.setOrderId(orderId);
        earning.setAmount(amount);
        earning.setStatus("EARNED");
        earning = earningRepository.save(earning);

        log.info("Recorded rider earning: agentId={}, orderId={}, amount={}", agentId, orderId, amount);

        return new EarningRecord(earning, false);
    }

    /**
     * Guarded transition: EARNED → PAID only. A replay of this call used
     * to re-flip WITHHELD rows to PAID with no approval trail.
     */
    @Transactional
    public void markEarningPaid(Long earningId) {
        int updated = earningRepository.markPaidIfEarned(earningId);
        if (updated == 0) {
            throw new BusinessException(
                    "Earning " + earningId + " is not in an payable (EARNED) state");
        }

        log.info("Marked earning as paid: earningId={}", earningId);
    }

    /**
     * Earnings of one agent, newest-last (read-only list mapping).
     */
    @Transactional(readOnly = true)
    public java.util.List<RiderEarning> earnings(Long agentId) {
        return earningRepository.findByAgentId(agentId);
    }

    /**
     * The ledger row is written in the caller's transaction (same tx as the
     * balance save): {@code balance_after} is taken from the PERSISTED wallet
     * state, never recomputed from a possibly stale read.
     */
    private void appendLedger(Long agentId, String type, BigDecimal amount,
                              BigDecimal balanceAfter, Long orderId) {
        CodWalletLedger entry = new CodWalletLedger();
        entry.setAgentId(agentId);
        entry.setOrderId(orderId);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        codWalletLedgerRepository.save(entry);
    }

    /** Outcome of {@link #recordEarning}: the earning, or a duplicate flag. */
    public record EarningRecord(RiderEarning earning, boolean duplicate) {
    }
}
