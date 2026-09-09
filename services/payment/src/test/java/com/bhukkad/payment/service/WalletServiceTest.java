package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.domain.WalletBalanceRepository;
import com.bhukkad.payment.domain.WalletTransaction;
import com.bhukkad.payment.domain.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock private WalletBalanceRepository balanceRepository;
    @Mock private WalletTransactionRepository transactionRepository;
    @InjectMocks private WalletService service;

    private WalletBalance balance(BigDecimal amount) {
        WalletBalance wb = new WalletBalance();
        wb.setCustomerId(1L);
        wb.setBalance(amount);
        return wb;
    }

    @Test
    void credit_existingWallet_addsBalance() {
        when(balanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(balance(new BigDecimal("50.00"))));
        when(balanceRepository.save(any(WalletBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletBalance result = service.credit(1L, new BigDecimal("100.00"), "TOPUP-1");

        assertThat(result.getBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void credit_newWallet_creates() {
        when(balanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.empty());
        when(balanceRepository.save(any(WalletBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletBalance result = service.credit(1L, new BigDecimal("200.00"), "TOPUP-2");

        assertThat(result.getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void debit_sufficient_balanceReduces() {
        when(balanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(balance(new BigDecimal("300.00"))));
        when(balanceRepository.save(any(WalletBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletBalance result = service.debit(1L, new BigDecimal("80.00"), "ORDER-1");

        assertThat(result.getBalance()).isEqualByComparingTo("220.00");
    }

    @Test
    void debit_insufficient_throws() {
        when(balanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(balance(new BigDecimal("10.00"))));
        assertThatThrownBy(() -> service.debit(1L, new BigDecimal("50.00"), "ORDER-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient");
    }

    @Test
    void debit_noWallet_throws() {
        when(balanceRepository.findByCustomerIdForUpdate(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.debit(9L, new BigDecimal("50.00"), "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void credit_negative_throws() {
        assertThatThrownBy(() -> service.credit(1L, new BigDecimal("-5.00"), "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void debit_negative_throws() {
        assertThatThrownBy(() -> service.debit(1L, new BigDecimal("-5.00"), "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    // ------------------------------------------------------------------
    // M-1 ledger accuracy: balance_after must be the post-atomic-update value.
    // ------------------------------------------------------------------

    @Test
    void credit_recordsPersistedBalanceAsBalanceAfter() {
        when(balanceRepository.findByCustomerIdForUpdate(1L))
                .thenReturn(Optional.of(balance(new BigDecimal("50.00"))));
        when(balanceRepository.save(any(WalletBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.credit(1L, new BigDecimal("100.00"), "TOPUP-L1");

        var captor = org.mockito.ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getBalanceAfter()).isEqualByComparingTo("150.00");
    }

    @Test
    void debit_recordsRealRemainingBalanceAsBalanceAfter() {
        when(balanceRepository.findByCustomerIdForUpdate(1L))
                .thenReturn(Optional.of(balance(new BigDecimal("300.00"))));
        when(balanceRepository.save(any(WalletBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletBalance result = service.debit(1L, new BigDecimal("80.00"), "ORDER-L1");

        assertThat(result.getBalance()).isEqualByComparingTo("220.00");
        var captor = org.mockito.ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("DEBIT");
        assertThat(captor.getValue().getBalanceAfter())
                .isEqualByComparingTo(result.getBalance())
                .isEqualByComparingTo("220.00");
    }

    @Test
    void concurrentLookingDebits_ledgerTracksTheRealRemainingBalance() {
        // One shared row entity returned for both locked reads simulates two
        // sequential debits racing on the same wallet: a ledger computed from
        // the FIRST read would record 300-50=250 for the second debit.
        WalletBalance shared = balance(new BigDecimal("300.00"));
        when(balanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(shared));
        when(balanceRepository.save(any(WalletBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.debit(1L, new BigDecimal("80.00"), "RACE-1");
        service.debit(1L, new BigDecimal("50.00"), "RACE-2");

        var captor = org.mockito.ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(WalletTransaction::getBalanceAfter)
                .extracting(v -> v.stripTrailingZeros().toPlainString())
                .containsExactly("220", "170");
        assertThat(shared.getBalance()).isEqualByComparingTo("170.00");
    }
}
