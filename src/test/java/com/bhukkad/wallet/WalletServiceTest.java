package com.bhukkad.wallet;

import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.identity.api.CustomerWalletSyncPort;
import com.bhukkad.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 data-ownership contract: the wallet locks and updates its OWN
 * {@code wallet_balances} row, records the ledger entry with plain customer/
 * payment ids, and syncs the identity read-model through the port — no
 * identity or payment entities involved.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final Long CUSTOMER_ID = 1L;

    @Mock
    private WalletBalanceRepository walletBalanceRepository;
    @Mock
    private WalletTransactionRepository walletTransactionRepository;
    @Mock
    private CustomerWalletSyncPort customerWalletSyncPort;

    @InjectMocks
    private WalletService service;

    private WalletBalance wallet;

    @BeforeEach
    void setUp() {
        wallet = WalletBalance.builder().customerId(CUSTOMER_ID).balance(100.0).build();
        lenient().when(walletBalanceRepository.findWithLockByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(wallet));
    }

    @Test
    void credit_addsToOwnedBalanceRecordsLedgerAndSyncs() {
        service.credit(CUSTOMER_ID, 50.0, WalletTransaction.TransactionType.REFERRAL_BONUS, null, "bonus");

        assertEquals(150.0, wallet.getBalance());
        verify(walletBalanceRepository).save(wallet);
        verify(walletTransactionRepository).save(org.mockito.ArgumentMatchers.argThat(tx ->
                tx.getCustomerId().equals(1L)
                        && tx.getAmount() == 50.0
                        && tx.getBalanceAfter() == 150.0
                        && tx.getType() == WalletTransaction.TransactionType.REFERRAL_BONUS));
        verify(customerWalletSyncPort).syncWalletBalance(CUSTOMER_ID, 150.0);
    }

    @Test
    void credit_throws_whenAmountNotPositive() {
        assertThrows(BusinessException.class,
                () -> service.credit(CUSTOMER_ID, 0, WalletTransaction.TransactionType.REFERRAL_BONUS, null, null));
        assertThrows(BusinessException.class,
                () -> service.credit(CUSTOMER_ID, -10, WalletTransaction.TransactionType.REFERRAL_BONUS, null, null));
        verify(walletBalanceRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    void debit_subtractsFromOwnedBalanceRecordsLedgerAndSyncs() {
        service.debit(CUSTOMER_ID, 40.0, WalletTransaction.TransactionType.ORDER_DEBIT, 7L, "order");

        assertEquals(60.0, wallet.getBalance());
        verify(walletBalanceRepository).save(wallet);
        verify(walletTransactionRepository).save(org.mockito.ArgumentMatchers.argThat(tx ->
                tx.getCustomerId().equals(1L)
                        && tx.getPaymentId().equals(7L)
                        && tx.getAmount() == 40.0
                        && tx.getBalanceAfter() == 60.0
                        && tx.getType() == WalletTransaction.TransactionType.ORDER_DEBIT));
        verify(customerWalletSyncPort).syncWalletBalance(CUSTOMER_ID, 60.0);
    }

    @Test
    void debit_throws_whenBalanceInsufficient() {
        assertThrows(BusinessException.class,
                () -> service.debit(CUSTOMER_ID, 500.0, WalletTransaction.TransactionType.ORDER_DEBIT, null, "order"));
        assertEquals(100.0, wallet.getBalance());
        verify(walletBalanceRepository, never()).save(any());
        verify(customerWalletSyncPort, never()).syncWalletBalance(anyLong(), anyDouble());
    }

    @Test
    void debit_throws_whenAmountNotPositive() {
        assertThrows(BusinessException.class,
                () -> service.debit(CUSTOMER_ID, 0, WalletTransaction.TransactionType.ORDER_DEBIT, null, null));
        assertThrows(BusinessException.class,
                () -> service.debit(CUSTOMER_ID, -5, WalletTransaction.TransactionType.ORDER_DEBIT, null, null));
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    void debit_createsBalanceRowOnFirstTouch_zeroBalanceRejected() {
        when(walletBalanceRepository.findWithLockByCustomerId(2L)).thenReturn(Optional.empty());
        when(walletBalanceRepository.save(any(WalletBalance.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThrows(BusinessException.class,
                () -> service.debit(2L, 10.0, WalletTransaction.TransactionType.ORDER_DEBIT, null, "first"));

        verify(walletBalanceRepository).save(any(WalletBalance.class));
        verify(customerWalletSyncPort, never()).syncWalletBalance(eq(2L), anyDouble());
    }

    @Test
    void credit_createsBalanceRowOnFirstTouch() {
        when(walletBalanceRepository.findWithLockByCustomerId(2L)).thenReturn(Optional.empty());
        when(walletBalanceRepository.save(any(WalletBalance.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.credit(2L, 25.0, WalletTransaction.TransactionType.TOP_UP, null, "first credit");

        verify(walletTransactionRepository).save(org.mockito.ArgumentMatchers.argThat(tx ->
                tx.getCustomerId().equals(2L) && tx.getBalanceAfter() == 25.0));
        verify(customerWalletSyncPort).syncWalletBalance(2L, 25.0);
    }

    @Test
    void operations_throws_whenCustomerIdMissing() {
        assertThrows(BusinessException.class,
                () -> service.credit(null, 10.0, WalletTransaction.TransactionType.TOP_UP, null, null));
        assertThrows(BusinessException.class,
                () -> service.debit(null, 10.0, WalletTransaction.TransactionType.ORDER_DEBIT, null, null));
        verify(walletBalanceRepository, never()).findWithLockByCustomerId(any());
    }
}
