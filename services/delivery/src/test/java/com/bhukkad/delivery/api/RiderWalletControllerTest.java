package com.bhukkad.delivery.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.delivery.service.DeliveryOpsService;
import com.bhukkad.delivery.service.RiderOpsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderWalletControllerTest {

    @Mock private RiderOpsService riderOpsService;
    @Mock private DeliveryOpsService deliveryOpsService;
    @InjectMocks private RiderWalletController controller;

    private static TokenPrincipal self(Long id) {
        return new TokenPrincipal(id, "r@bhukkad.in", "DELIVERY_AGENT");
    }

    @Test
    void codWalletRead_enforcesSelfOrAdmin() {
        when(riderOpsService.getCodWallet(2L)).thenReturn(Map.of("balance", 100));

        assertThat(controller.getCodWallet(self(2L), 2L).getBody())
                .containsEntry("balance", 100);
        assertThat(controller.getCodWallet(
                new TokenPrincipal(9L, "ops@bhukkad.in", "ADMIN"), 2L).getBody()).isNotNull();
    }

    @Test
    void codWalletRead_otherRiderDenied() {
        assertThatThrownBy(() -> controller.getCodWallet(self(3L), 2L))
                .isInstanceOf(AccessDeniedException.class);
        verify(riderOpsService, never()).getCodWallet(2L);
    }

    @Test
    void creditAndDebit_delegateAmounts() {
        when(riderOpsService.creditCod(2L, new BigDecimal("50.00")))
                .thenReturn(Map.of("balance", 150));
        when(riderOpsService.debitCod(2L, new BigDecimal("20.00")))
                .thenReturn(Map.of("balance", 130));

        assertThat(controller.creditCodWallet(2L, new BigDecimal("50.00")).getBody())
                .containsEntry("balance", 150);
        assertThat(controller.debitCodWallet(2L, new BigDecimal("20.00")).getBody())
                .containsEntry("balance", 130);
    }

    @Test
    void recordEarning_explicitAmountRoutesThroughDeliveryOpsOverride() {
        when(deliveryOpsService.recordEarning(2L, 8L, new BigDecimal("77.00")))
                .thenReturn(Map.of("amount", 77.00));

        assertThat(controller.recordEarning(2L, 8L, new BigDecimal("77.00")).getBody())
                .containsEntry("amount", 77.00);
        verify(riderOpsService, never()).recordEarning(2L, 8L);
    }

    @Test
    void recordEarning_withoutAmountUsesConfiguredRate() {
        when(riderOpsService.recordEarning(2L, 8L)).thenReturn(Map.of("status", "EARNED"));

        assertThat(controller.recordEarning(2L, 8L, null).getBody())
                .containsEntry("status", "EARNED");
        verify(deliveryOpsService, never()).recordEarning(2L, 8L, null);
    }

    @Test
    void earningsList_enforcesSelfOrAdminAndDelegates() {
        List<Map<String, Object>> rows = List.of(Map.of("amount", 40));
        when(riderOpsService.getEarnings(2L)).thenReturn(rows);

        assertThat(controller.getEarnings(self(2L), 2L).getBody()).isEqualTo(rows);
        assertThatThrownBy(() -> controller.getEarnings(self(4L), 2L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void markEarningPaid_delegates() {
        when(riderOpsService.markEarningPaid(6L)).thenReturn(Map.of("status", "PAID"));

        assertThat(controller.markEarningPaid(6L).getBody()).containsEntry("status", "PAID");
    }
}
