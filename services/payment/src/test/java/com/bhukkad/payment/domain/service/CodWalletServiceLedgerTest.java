package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.entity.AgentCodWallet;
import com.bhukkad.payment.domain.entity.CodWalletLedger;
import com.bhukkad.payment.domain.entity.RiderEarning;
import com.bhukkad.payment.domain.repository.AgentCodWalletRepository;
import com.bhukkad.payment.domain.repository.CodWalletLedgerRepository;
import com.bhukkad.payment.domain.repository.RiderEarningRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodWalletServiceLedgerTest {

    @Mock private AgentCodWalletRepository codWalletRepository;
    @Mock private CodWalletLedgerRepository codWalletLedgerRepository;
    @Mock private RiderEarningRepository earningRepository;
    @InjectMocks private CodWalletService service;

    @Test
    void balance_existingWallet_returnsBalance() {
        AgentCodWallet wallet = new AgentCodWallet();
        wallet.setAgentId(1L);
        wallet.setBalance(new BigDecimal("250.00"));
        when(codWalletRepository.findByAgentId(1L)).thenReturn(Optional.of(wallet));

        assertThat(service.balance(1L)).isEqualByComparingTo("250.00");
    }

    @Test
    void balance_missingWallet_readsAsZeroWithoutInsert() {
        when(codWalletRepository.findByAgentId(1L)).thenReturn(Optional.empty());

        assertThat(service.balance(1L)).isEqualByComparingTo("0.00");
        verify(codWalletRepository, never()).save(any());
    }

    @Test
    void credit_nonPositive_throws() {
        assertThatThrownBy(() -> service.credit(1L, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void credit_missingWallet_mintsZeroWalletThenAppendsLedgerRow() {
        when(codWalletRepository.findByAgentIdForUpdate(1L)).thenReturn(Optional.empty());
        when(codWalletRepository.save(any(AgentCodWallet.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentCodWallet result = service.credit(1L, new BigDecimal("100.00"));

        assertThat(result.getAgentId()).isEqualTo(1L);
        assertThat(result.getBalance()).isEqualByComparingTo("100.00");
        ArgumentCaptor<CodWalletLedger> ledger = ArgumentCaptor.forClass(CodWalletLedger.class);
        verify(codWalletLedgerRepository).save(ledger.capture());
        assertThat(ledger.getValue().getType()).isEqualTo("CREDIT");
        assertThat(ledger.getValue().getBalanceAfter()).isEqualByComparingTo("100.00");
    }

    @Test
    void credit_existingWallet_addsAndRecordsLedger() {
        AgentCodWallet wallet = new AgentCodWallet();
        wallet.setAgentId(2L);
        wallet.setBalance(new BigDecimal("50.00"));
        when(codWalletRepository.findByAgentIdForUpdate(2L)).thenReturn(Optional.of(wallet));
        when(codWalletRepository.save(any(AgentCodWallet.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentCodWallet result = service.credit(2L, new BigDecimal("25.00"));

        assertThat(result.getBalance()).isEqualByComparingTo("75.00");
        verify(codWalletLedgerRepository).save(any(CodWalletLedger.class));
    }

    @Test
    void debit_nonPositive_throws() {
        assertThatThrownBy(() -> service.debit(1L, new BigDecimal("-2.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Debit amount must be positive");
    }

    @Test
    void debit_overdraw_throws() {
        AgentCodWallet wallet = new AgentCodWallet();
        wallet.setAgentId(1L);
        wallet.setBalance(new BigDecimal("10.00"));
        when(codWalletRepository.findByAgentIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> service.debit(1L, new BigDecimal("10.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient");
    }

    @Test
    void markEarningPaid_replayOnNonEarnedRow_throws() {
        when(earningRepository.markPaidIfEarned(3L)).thenReturn(0);

        assertThatThrownBy(() -> service.markEarningPaid(3L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void markEarningPaid_transitionsEarnedRow() {
        when(earningRepository.markPaidIfEarned(3L)).thenReturn(1);

        assertThatCode(() -> service.markEarningPaid(3L)).doesNotThrowAnyException();
    }

    @Test
    void earnings_delegatesToRepository() {
        RiderEarning earning = new RiderEarning();
        when(earningRepository.findByAgentId(4L)).thenReturn(List.of(earning));

        assertThat(service.earnings(4L)).containsExactly(earning);
    }

    @Test
    void recordEarning_existingRow_returnsDuplicateWithoutSave() {
        when(earningRepository.countByAgentIdAndOrderId(1L, 2L)).thenReturn(1L);

        CodWalletService.EarningRecord record =
                service.recordEarning(1L, 2L, new BigDecimal("40.00"));

        assertThat(record.duplicate()).isTrue();
        verify(earningRepository, never()).save(any());
    }

    @Test
    void recordEarning_nonPositiveAmount_throws() {
        assertThatThrownBy(() -> service.recordEarning(1L, 2L, new BigDecimal("-1.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }
}
