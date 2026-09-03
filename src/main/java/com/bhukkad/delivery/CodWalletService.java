package com.bhukkad.delivery;

import com.bhukkad.entity.AgentCodWallet;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.repository.AgentCodWalletRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** Cash-on-delivery wallet: collections, deposits, balance and reconciliation. */
@Service
@RequiredArgsConstructor
@Transactional
public class CodWalletService {

    private final AgentCodWalletRepository walletRepository;

    public AgentCodWallet recordCollection(Long agentId, Double amount) {
        double value = requirePositiveAmount(amount);
        AgentCodWallet wallet = walletRepository.getOrCreateByAgentId(agentId);
        wallet.setTotalCashCollected(PriceCalculator.roundToTwoDecimals(wallet.getTotalCashCollected() + value));
        return walletRepository.save(wallet);
    }

    public AgentCodWallet recordDeposit(Long agentId, Double amount) {
        double value = requirePositiveAmount(amount);
        AgentCodWallet wallet = walletRepository.getOrCreateByAgentId(agentId);
        wallet.setTotalCashDeposited(PriceCalculator.roundToTwoDecimals(wallet.getTotalCashDeposited() + value));
        return walletRepository.save(wallet);
    }

    @Transactional(readOnly = true)
    public double getBalance(Long agentId) {
        return walletRepository.findByAgentId(agentId)
                .map(AgentCodWallet::getBalance)
                .orElse(0.0);
    }

    @Transactional(readOnly = true)
    public Optional<AgentCodWallet> getWallet(Long agentId) {
        return walletRepository.findByAgentId(agentId);
    }

    /**
     * Verifies the wallet balance against the expected cash and marks the wallet
     * as reconciled. Throws {@link BusinessException} when the balances differ.
     */
    public AgentCodWallet reconcile(Long agentId, Double expectedBalance) {
        AgentCodWallet wallet = walletRepository.findByAgentId(agentId)
                .orElseThrow(() -> new BusinessException("No COD wallet found for agent; nothing to reconcile"));
        double actual = wallet.getBalance();
        if (expectedBalance == null || PriceCalculator.roundToTwoDecimals(expectedBalance) != actual) {
            throw new BusinessException("Reconciliation mismatch: expected " + expectedBalance
                    + " but wallet balance is " + PriceCalculator.roundToTwoDecimals(actual));
        }
        wallet.setLastReconciledAt(LocalDateTime.now());
        return walletRepository.save(wallet);
    }

    private double requirePositiveAmount(Double amount) {
        if (amount == null || amount <= 0) {
            throw new BusinessException("Amount must be positive");
        }
        return amount;
    }
}