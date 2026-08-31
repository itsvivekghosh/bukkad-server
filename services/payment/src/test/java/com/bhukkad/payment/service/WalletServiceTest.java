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
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(new BigDecimal("50.00"))));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletBalance result = service.credit(1L, new BigDecimal("100.00"), "TOPUP-1");

        assertThat(result.getBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void credit_newWallet_creates() {
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.empty());
        when(balanceRepository.save(any(WalletBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletBalance result = service.credit(1L, new BigDecimal("200.00"), "TOPUP-2");

        assertThat(result.getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void debit_sufficient_balanceReduces() {
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(new BigDecimal("300.00"))));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletBalance result = service.debit(1L, new BigDecimal("80.00"), "ORDER-1");

        assertThat(result.getBalance()).isEqualByComparingTo("220.00");
    }

    @Test
    void debit_insufficient_throws() {
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(new BigDecimal("10.00"))));
        assertThatThrownBy(() -> service.debit(1L, new BigDecimal("50.00"), "ORDER-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient");
    }

    @Test
    void debit_noWallet_throws() {
        when(balanceRepository.findByCustomerId(9L)).thenReturn(Optional.empty());
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

    @Test
    void balance_existing_returnsBalance() {
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(new BigDecimal("42.00"))));

        assertThat(service.balance(1L).getBalance()).isEqualByComparingTo("42.00");
    }

    @Test
    void balance_missing_returnsNull() {
        when(balanceRepository.findByCustomerId(9L)).thenReturn(Optional.empty());

        assertThat(service.balance(9L)).isNull();
    }

    @Test
    void transactions_delegatesToRepository() {
        when(transactionRepository.findByCustomerId(1L)).thenReturn(java.util.List.of());

        assertThat(service.transactions(1L)).isEmpty();
        org.mockito.Mockito.verify(transactionRepository).findByCustomerId(1L);
    }
}
