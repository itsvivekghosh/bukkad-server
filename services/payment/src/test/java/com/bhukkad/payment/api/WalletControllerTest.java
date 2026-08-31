package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.service.PaymentService;
import com.bhukkad.payment.service.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

    @Mock private WalletService walletService;
    @Mock private PaymentService paymentService;
    @InjectMocks private WalletController controller;

    @Test
    void balance_returnsWalletForCustomer() {
        WalletBalance balance = new WalletBalance();
        balance.setCustomerId(2L);
        balance.setBalance(new BigDecimal("99.00"));
        when(walletService.balance(2L)).thenReturn(balance);

        assertThat(controller.balance(2L).getBalance()).isEqualByComparingTo("99.00");
    }

    @Test
    void topUp_processesPaymentAndReturnsBalance() {
        WalletBalance balance = new WalletBalance();
        balance.setCustomerId(2L);
        balance.setBalance(new BigDecimal("200.00"));
        when(walletService.balance(2L)).thenReturn(balance);

        WalletBalance result = controller.topUp(2L, new BigDecimal("200.00"), "idem-topup");

        verify(paymentService).processPayment(0L, 2L, new BigDecimal("200.00"), "idem-topup");
        assertThat(result.getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void payments_returnsEmptyList() {
        assertThat((java.util.List<?>) controller.payments(2L)).isEmpty();
    }
}
