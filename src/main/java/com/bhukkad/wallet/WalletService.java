package com.bhukkad.wallet;

import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.identity.api.CustomerWalletSyncPort;
import com.bhukkad.repository.WalletTransactionRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallet ledger operations. Phase 2 data ownership: the authoritative balance
 * lives in {@code wallet_balances} (this domain's own table) and is serialised
 * with a pessimistic write lock on that row. The identity-owned
 * {@code customers.wallet_balance} column is kept in sync via
 * {@link CustomerWalletSyncPort} inside the same transaction so existing
 * readers (pricing, profile, export) are unaffected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletBalanceRepository walletBalanceRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final CustomerWalletSyncPort customerWalletSyncPort;

    @Transactional
    public void credit(Long customerId, double amount, WalletTransaction.TransactionType type,
                       Long paymentId, String description) {
        if (amount <= 0) {
            throw new BusinessException("Credit amount must be positive");
        }
        WalletBalance wallet = lockOrCreate(customerId);
        double newBalance = PriceCalculator.roundToTwoDecimals(wallet.getBalance() + amount);
        wallet.setBalance(newBalance);
        walletBalanceRepository.save(wallet);
        recordTransaction(customerId, paymentId, type, amount, newBalance, description);
        customerWalletSyncPort.syncWalletBalance(customerId, newBalance);
    }

    @Transactional
    public void debit(Long customerId, double amount, WalletTransaction.TransactionType type,
                      Long paymentId, String description) {
        if (amount <= 0) {
            throw new BusinessException("Debit amount must be positive");
        }
        WalletBalance wallet = lockOrCreate(customerId);
        if (wallet.getBalance() < amount) {
            throw new BusinessException("Insufficient wallet balance");
        }
        double newBalance = PriceCalculator.roundToTwoDecimals(wallet.getBalance() - amount);
        wallet.setBalance(newBalance);
        walletBalanceRepository.save(wallet);
        recordTransaction(customerId, paymentId, type, -amount, newBalance, description);
        customerWalletSyncPort.syncWalletBalance(customerId, newBalance);
    }

    /**
     * Locks the wallet's own balance row for the duration of the caller's
     * transaction, creating it on first touch (new customers start at 0).
     */
    private WalletBalance lockOrCreate(Long customerId) {
        if (customerId == null) {
            throw new BusinessException("Customer id is required for wallet operations");
        }
        return walletBalanceRepository.findWithLockByCustomerId(customerId)
                .orElseGet(() -> walletBalanceRepository.save(
                        WalletBalance.builder().customerId(customerId).balance(0.0).build()));
    }

    private void recordTransaction(Long customerId, Long paymentId, WalletTransaction.TransactionType type,
                                   double signedAmount, double balanceAfter, String description) {
        WalletTransaction tx = new WalletTransaction();
        tx.setCustomerId(customerId);
        tx.setPaymentId(paymentId);
        tx.setType(type);
        tx.setAmount(PriceCalculator.roundToTwoDecimals(Math.abs(signedAmount)));
        tx.setBalanceAfter(balanceAfter);
        tx.setDescription(description);
        walletTransactionRepository.save(tx);
        log.debug("WALLET_TX | customerId={} | type={} | amount={} | balanceAfter={}",
                customerId, type, signedAmount, balanceAfter);
    }
}
