package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.AgentCodWallet;
import com.bhukkad.payment.domain.AgentCodWalletRepository;
import com.bhukkad.payment.domain.CodWalletLedger;
import com.bhukkad.payment.domain.CodWalletLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Agent COD wallet money path (audit V-02 finish — extracted from
 * {@code DeliveryPaymentController}, which carried the transactions on its
 * HTTP handlers). Every balance mutation runs here inside ONE transaction
 * that also appends the {@code cod_wallet_ledger} entry, so a committed
 * balance and its ledger row can never diverge.
 *
 * <p>Guards inherited from the controller verbatim: signum checks reject
 * zero/negative amounts (a negative "credit" was a debit bypassing the
 * balance check), the debit path holds a pessimistic FOR UPDATE lock across
 * the check-then-act, and reads never insert (no INSERT-on-GET). The
 * {@code @Version} column (V12) is the second fence against stale
 * detached-entity saves, mirroring the customer wallet (V-01/{@link WalletService}).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodWalletService {

    static final String LEDGER_CREDIT = "CREDIT";
    static final String LEDGER_DEBIT = "DEBIT";

    private final AgentCodWalletRepository codWalletRepository;
    private final CodWalletLedgerRepository ledgerRepository;

    /**
     * Read-only balance view: a missing wallet reads as zero (no row, no
     * transaction write — the GET stays a GET).
     */
    @Transactional(readOnly = true)
    public BigDecimal balance(Long agentId) {
        return codWalletRepository.findByAgentId(agentId)
                .map(AgentCodWallet::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Credits undelivered COD cash. {@code orderId} is an optional ledger
     * attribution only — it does not gate idempotency (the endpoint contract
     * stays amount-only for existing callers).
     */
    @Transactional
    public AgentCodWallet credit(Long agentId, BigDecimal amount, Long orderId) {
        if (amount == null || amount.signum() <= 0) {
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

        appendLedger(agentId, orderId, LEDGER_CREDIT, amount, wallet.getBalance());

        log.info("Credited COD wallet: agentId={}, amount={}, newBalance={}", agentId, amount, wallet.getBalance());
        return wallet;
    }

    @Transactional
    public AgentCodWallet debit(Long agentId, BigDecimal amount, Long orderId) {
        if (amount == null || amount.signum() <= 0) {
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

        appendLedger(agentId, orderId, LEDGER_DEBIT, amount, wallet.getBalance());

        log.info("Debited COD wallet: agentId={}, amount={}, newBalance={}", agentId, amount, wallet.getBalance());
        return wallet;
    }

    /**
     * Appends the ledger row with the balance AS PERSISTED (M-1: never a
     * recomputed in-memory sum). Joins the caller's transaction; a ledger
     * failure rolls the balance change back with it.
     */
    private void appendLedger(Long agentId, Long orderId, String type,
                              BigDecimal amount, BigDecimal balanceAfter) {
        CodWalletLedger entry = new CodWalletLedger();
        entry.setAgentId(agentId);
        entry.setOrderId(orderId);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        ledgerRepository.save(entry);
    }
}
