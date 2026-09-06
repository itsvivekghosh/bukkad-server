package com.bhukkad.payment.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.service.PaymentService;
import com.bhukkad.payment.service.WalletService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

    @Mock private WalletService walletService;
    @Mock private PaymentService paymentService;
    @Mock private com.bhukkad.payment.mapper.PaymentMapper paymentMapper;
    @InjectMocks private WalletController controller;

    private TokenPrincipal principal(Long userId) {
        return new TokenPrincipal(userId, "u@example.com", "CUSTOMER");
    }

    @Test
    void balance_returnsWalletForCustomer() {
        WalletBalance balance = new WalletBalance();
        balance.setCustomerId(2L);
        balance.setBalance(new BigDecimal("99.00"));
        when(walletService.balance(2L)).thenReturn(balance);
        WalletResponse response = WalletResponse.builder()
                .customerId(2L).balance(new BigDecimal("99.00")).build();
        when(paymentMapper.toWalletResponse(balance)).thenReturn(response);

        assertThat(controller.balance(principal(2L), 2L).getBalance()).isEqualByComparingTo("99.00");
    }

    @Test
    void balance_otherCustomersWallet_throws() {
        assertThatThrownBy(() -> controller.balance(principal(2L), 3L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void topUp_processesPaymentAndReturnsBalance() {
        WalletBalance balance = new WalletBalance();
        balance.setCustomerId(2L);
        balance.setBalance(new BigDecimal("200.00"));
        when(walletService.balance(2L)).thenReturn(balance);
        WalletResponse response = WalletResponse.builder()
                .customerId(2L).balance(new BigDecimal("200.00")).build();
        when(paymentMapper.toWalletResponse(balance)).thenReturn(response);

        WalletTopUpRequest request = new WalletTopUpRequest();
        request.setCustomerId(2L);
        request.setAmount(new BigDecimal("200.00"));

        WalletResponse result = controller.topUp(principal(2L), 2L, request, "idem-topup");

        verify(paymentService).processPayment(0L, 2L, new BigDecimal("200.00"),
                Payment.METHOD_WALLET, "idem-topup");
        assertThat(result.getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void transactions_returnsEmptyList() {
        when(walletService.transactionsPage(2L, 0, 10)).thenReturn(List.of());

        var response = controller.transactions(principal(2L), 0, 10);

        assertThat((List<?>) response.get("items")).isEmpty();
        assertThat(response.get("page")).isEqualTo(0);
        assertThat(response.get("size")).isEqualTo(10);
        assertThat(response.get("hasNext")).isEqualTo(false);
    }
}
