package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.util.CursorUtils;
import com.bhukkad.common.util.PaginationUtils;
import com.bhukkad.payment.domain.entity.WalletBalance;
import com.bhukkad.payment.domain.entity.WalletTransaction;
import com.bhukkad.payment.domain.repository.WalletBalanceRepository;
import com.bhukkad.payment.domain.repository.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletServicePaginationCursorTest {

    @Mock private WalletBalanceRepository balanceRepository;
    @Mock private WalletTransactionRepository transactionRepository;
    @InjectMocks private WalletService service;

    private static WalletTransaction tx(Long id, LocalDateTime createdAt) {
        WalletTransaction t = new WalletTransaction();
        t.setId(id);
        t.setCustomerId(1L);
        t.setType("CREDIT");
        t.setAmount(new BigDecimal("10.00"));
        t.setBalanceAfter(new BigDecimal("110.00"));
        t.setCreatedAt(createdAt);
        return t;
    }

    @Test
    void transactionsPage_clampsNegativePageAndOversizedRequest() {
        when(transactionRepository.pageNewestFirst(eq(1L), any(Pageable.class)))
                .thenReturn(List.of());

        service.transactionsPage(1L, -5, 10_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).pageNewestFirst(eq(1L), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize())
                .isEqualTo(PaginationUtils.MAX_PAGE_SIZE);
    }

    @Test
    void transactionsPage_clampsSizeBelowOne() {
        when(transactionRepository.pageNewestFirst(eq(1L), any(Pageable.class)))
                .thenReturn(List.of());

        service.transactionsPage(1L, 2, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).pageNewestFirst(eq(1L), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    void transactionsByCursor_withoutCursor_usesEndSentinels() {
        when(transactionRepository.afterCursor(eq(1L), any(LocalDateTime.class), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        WalletService.CursorPage page = service.transactionsByCursor(1L, null, 5);

        verify(transactionRepository).afterCursor(1L,
                CursorUtils.END_OF_TIME, CursorUtils.END_OF_ID, PageRequest.of(0, 6));
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void transactionsByCursor_overflowPage_setsHasNextAndEncodesLastRow() {
        LocalDateTime first = LocalDateTime.of(2026, 3, 1, 12, 0);
        List<WalletTransaction> batch = List.of(
                tx(3L, first.plusDays(3)),
                tx(2L, first.plusDays(2)),
                tx(1L, first.plusDays(1)));
        when(transactionRepository.afterCursor(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(batch);

        WalletService.CursorPage page = service.transactionsByCursor(1L, " ", 2);

        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).hasSize(2);
        String cursor = CursorUtils.decode(page.nextCursor()).orElseThrow().toString();
        assertThat(cursor).contains("id=2");
    }

    @Test
    void transactionsByCursor_withValidCursor_decodesToRepositoryArguments() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 4, 9, 30);
        String cursor = CursorUtils.encode(createdAt, 42L);
        when(transactionRepository.afterCursor(eq(1L), eq(createdAt), eq(42L), any(Pageable.class)))
                .thenReturn(List.of(tx(42L, createdAt)));

        WalletService.CursorPage page = service.transactionsByCursor(1L, cursor, 10);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void transactionsByCursor_exactPageBoundary_hasNoNext() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        when(transactionRepository.afterCursor(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(tx(1L, now), tx(2L, now)));

        WalletService.CursorPage page = service.transactionsByCursor(1L, null, 2);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void transactions_returnsLedgerRows() {
        WalletTransaction t = tx(1L, LocalDateTime.now());
        when(transactionRepository.findByCustomerId(1L)).thenReturn(List.of(t));

        assertThat(service.transactions(1L)).containsExactly(t);
    }

    @Test
    void balance_missingWallet_readsAsNull() {
        when(balanceRepository.findByCustomerId(9L)).thenReturn(Optional.empty());

        assertThat(service.balance(9L)).isNull();
    }

    @Test
    void debit_reportsPersistedBalance_neverRecomputesIt() {
        WalletBalance locked = new WalletBalance();
        locked.setCustomerId(1L);
        locked.setBalance(new BigDecimal("100.00"));
        WalletBalance persisted = new WalletBalance();
        persisted.setCustomerId(1L);
        persisted.setBalance(new BigDecimal("40.00"));
        when(balanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(locked));
        when(balanceRepository.save(any(WalletBalance.class))).thenReturn(persisted);

        WalletBalance result = service.debit(1L, new BigDecimal("60.00"), "pay:1");

        assertThat(result).isSameAs(persisted);
        ArgumentCaptor<WalletTransaction> ledger = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(ledger.capture());
        assertThat(ledger.getValue().getBalanceAfter()).isEqualByComparingTo("40.00");
        assertThat(ledger.getValue().getType()).isEqualTo("DEBIT");
    }

    @Test
    void debit_missingWallet_throws() {
        when(balanceRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.debit(1L, BigDecimal.ONE, "r"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void credit_negativeAmount_throws() {
        assertThatThrownBy(() -> service.credit(1L, new BigDecimal("-1.00"), "r"))
                .isInstanceOf(BusinessException.class);
    }
}
