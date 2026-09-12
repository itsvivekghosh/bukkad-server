package com.bhukkad.payment.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.payment.api.dto.response.WalletResponse;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.entity.WalletBalance;
import com.bhukkad.payment.domain.entity.WalletTransaction;
import com.bhukkad.payment.domain.mapper.PaymentMapper;
import com.bhukkad.payment.domain.service.PaymentService;
import com.bhukkad.payment.domain.service.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletControllerSelfSurfaceTest {

    @Mock private WalletService walletService;
    @Mock private PaymentService paymentService;
    @Mock private PaymentMapper paymentMapper;
    @InjectMocks private WalletController controller;

    private static TokenPrincipal principal(Long userId) {
        return new TokenPrincipal(userId, "u@example.com", "CUSTOMER");
    }

    private static WalletBalance balance(String value) {
        WalletBalance b = new WalletBalance();
        b.setCustomerId(2L);
        b.setBalance(new BigDecimal(value));
        return b;
    }

    private static WalletResponse mapped(String value) {
        return WalletResponse.builder().customerId(2L).balance(new BigDecimal(value)).build();
    }

    @Test
    void selfBalance_usesJwtSubject() {
        WalletBalance wallet = balance("10.00");
        when(walletService.balance(2L)).thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("10.00"));

        assertThat(controller.selfBalance(principal(2L)).getBalance())
                .isEqualByComparingTo("10.00");
    }

    @Test
    void selfBalance_anonymous_throwsUnauthorized() {
        assertThatThrownBy(() -> controller.selfBalance(null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.selfBalance(new TokenPrincipal(null, "x@y.z", "CUSTOMER")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void selfAddMoney_creditsWithIdempotencyReference() {
        WalletBalance wallet = balance("120.00");
        when(walletService.credit(2L, new BigDecimal("20.00"), "ADD_MONEY:idem-1"))
                .thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("120.00"));

        assertThat(controller.selfAddMoney(principal(2L), new BigDecimal("20.00"), "idem-1")
                .getBalance()).isEqualByComparingTo("120.00");
    }

    @Test
    void selfAddMoney_missingHeader_usesEmptyReference() {
        WalletBalance wallet = balance("5.00");
        when(walletService.credit(2L, new BigDecimal("5.00"), "ADD_MONEY:")).thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("5.00"));

        controller.selfAddMoney(principal(2L), new BigDecimal("5.00"), null);

        verify(walletService).credit(2L, new BigDecimal("5.00"), "ADD_MONEY:");
    }

    @Test
    void selfAddMoney_nonPositive_throws() {
        assertThatThrownBy(() -> controller.selfAddMoney(principal(2L), BigDecimal.ZERO, "k"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.selfAddMoney(principal(2L), null, "k"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void selfTopUp_explicitKey_routesThroughChargePath() {
        WalletBalance wallet = balance("300.00");
        when(walletService.balance(2L)).thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("300.00"));

        controller.selfTopUp(principal(2L), new BigDecimal("100.00"), "key-9");

        verify(paymentService).processPayment(0L, 2L, new BigDecimal("100.00"),
                Payment.METHOD_WALLET, "key-9");
    }

    @Test
    void selfTopUp_blankKey_derivesSyntheticIdempotencyKey() {
        WalletBalance wallet = balance("20.00");
        when(walletService.balance(2L)).thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("20.00"));

        controller.selfTopUp(principal(2L), new BigDecimal("20.00"), "   ");

        verify(paymentService).processPayment(0L, 2L, new BigDecimal("20.00"),
                Payment.METHOD_WALLET, "self-topup:2:20.00");
        verify(walletService, never()).credit(anyLong(), any(), anyString());
    }

    @Test
    void selfTopUp_nonPositive_throws() {
        assertThatThrownBy(() -> controller.selfTopUp(principal(2L), new BigDecimal("-1.00"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void transactions_mapsRowsAndHasNextSignal() {
        WalletTransaction t = new WalletTransaction();
        when(walletService.transactionsPage(2L, 1, 1)).thenReturn(List.of(t));
        when(paymentMapper.toWalletTransactionResponse(t)).thenReturn(
                com.bhukkad.payment.api.dto.response.WalletTransactionResponse.builder().id(1L).build());

        Map<String, Object> body = controller.transactions(principal(2L), 1, 1);

        assertThat(body).containsEntry("page", 1);
        assertThat(body).containsEntry("hasNext", true);
        assertThat((List<?>) body.get("items")).hasSize(1);
    }

    @Test
    void transactions_anonymous_throwsUnauthorized() {
        assertThatThrownBy(() -> controller.transactions(null, 0, 10))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void transactionsByCursor_surfacesNextCursorAndSize() {
        WalletTransaction t = new WalletTransaction();
        when(walletService.transactionsByCursor(2L, "cur", 5)).thenReturn(
                new WalletService.CursorPage(List.of(t), "next-1", true));
        when(paymentMapper.toWalletTransactionResponse(t)).thenReturn(
                com.bhukkad.payment.api.dto.response.WalletTransactionResponse.builder().id(1L).build());

        Map<String, Object> body = controller.transactionsByCursor(principal(2L), "cur", 5);

        assertThat(body)
                .containsEntry("nextCursor", "next-1")
                .containsEntry("hasNext", true)
                .containsEntry("size", 1);
    }

    @Test
    void transactionsByCursor_nullNextCursorBecomesEmptyString() {
        when(walletService.transactionsByCursor(eq(2L), any(), anyInt())).thenReturn(
                new WalletService.CursorPage(List.of(), null, false));

        Map<String, Object> body = controller.transactionsByCursor(principal(2L), null, 10);

        assertThat(body).containsEntry("nextCursor", "");
        assertThat(body).containsEntry("hasNext", false);
    }

    @Test
    void selfTransactions_delegateToCursorSurface() {
        when(walletService.transactionsPage(2L, 0, 10)).thenReturn(List.of());

        Map<String, Object> viaSelf = controller.selfTransactions(principal(2L), 0, 10);
        assertThat((List<?>) viaSelf.get("items")).isEmpty();
    }

    @Test
    void selfTransactionsByCursor_delegates() {
        when(walletService.transactionsByCursor(2L, null, 3)).thenReturn(
                new WalletService.CursorPage(List.of(), null, false));

        assertThat(controller.selfTransactionsByCursor(principal(2L), null, 3))
                .containsEntry("hasNext", false);
    }

    @Test
    void internalCredit_reportsPersistedBalance() {
        WalletBalance wallet = balance("80.00");
        when(walletService.credit(2L, new BigDecimal("30.00"), "CREDIT:ref"))
                .thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("80.00"));

        var request = new WalletController.InternalWalletRequest(2L, new BigDecimal("30.00"), "ref");

        assertThat(controller.credit(request).getBalance()).isEqualByComparingTo("80.00");
    }

    @Test
    void internalCredit_nullReference_usesEmptySuffix() {
        WalletBalance wallet = balance("10.00");
        when(walletService.credit(anyLong(), any(), anyString())).thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("10.00"));

        controller.credit(new WalletController.InternalWalletRequest(2L, BigDecimal.ONE, null));

        verify(walletService).credit(2L, BigDecimal.ONE, "CREDIT:");
    }

    @Test
    void internalDebit_nullReference_usesEmptySuffix() {
        WalletBalance wallet = balance("9.00");
        when(walletService.debit(anyLong(), any(), anyString())).thenReturn(wallet);
        when(paymentMapper.toWalletResponse(wallet)).thenReturn(mapped("9.00"));

        controller.debit(new WalletController.InternalWalletRequest(2L, BigDecimal.ONE, null));

        verify(walletService).debit(2L, BigDecimal.ONE, "DEBIT:");
    }
}
