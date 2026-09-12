package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.entity.AgentCodWallet;
import com.bhukkad.payment.domain.entity.RiderEarning;
import com.bhukkad.payment.domain.service.CodWalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryPaymentControllerTest {

    @Mock private CodWalletService codWalletService;
    @InjectMocks private DeliveryPaymentController controller;

    private static AgentCodWallet wallet(Long agentId, String balance) {
        AgentCodWallet w = new AgentCodWallet();
        w.setAgentId(agentId);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    @Test
    void getCodWallet_reportsBalance() {
        when(codWalletService.balance(1L)).thenReturn(new BigDecimal("31.40"));

        var response = controller.getCodWallet(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("agentId", 1L)
                .containsEntry("balance", new BigDecimal("31.40"));
    }

    @Test
    void credit_returnsPersistedWallet() {
        when(codWalletService.credit(2L, new BigDecimal("10.00")))
                .thenReturn(wallet(2L, "60.00"));

        var body = controller.creditCodWallet(2L, new BigDecimal("10.00")).getBody();

        assertThat(body)
                .containsEntry("agentId", 2L)
                .containsEntry("credited", new BigDecimal("10.00"));
        assertThat((BigDecimal) body.get("balance")).isEqualByComparingTo("60.00");
    }

    @Test
    void debit_returnsPersistedWallet() {
        when(codWalletService.debit(2L, new BigDecimal("10.00")))
                .thenReturn(wallet(2L, "40.00"));

        var body = controller.debitCodWallet(2L, new BigDecimal("10.00")).getBody();

        assertThat(body).containsEntry("debited", new BigDecimal("10.00"));
        assertThat((BigDecimal) body.get("balance")).isEqualByComparingTo("40.00");
    }

    @Test
    void recordEarning_duplicate_reportsFlag() {
        when(codWalletService.recordEarning(1L, 2L, new BigDecimal("5.00")))
                .thenReturn(new CodWalletService.EarningRecord(null, true));

        var body = controller.recordEarning(1L, 2L, new BigDecimal("5.00")).getBody();

        assertThat(body)
                .containsEntry("duplicate", true)
                .containsEntry("orderId", 2L);
    }

    @Test
    void recordEarning_new_mintsEarning() {
        RiderEarning earning = new RiderEarning();
        earning.setId(7L);
        earning.setAgentId(1L);
        earning.setOrderId(2L);
        earning.setAmount(new BigDecimal("33.00"));
        earning.setStatus("EARNED");
        when(codWalletService.recordEarning(1L, 2L, new BigDecimal("33.00")))
                .thenReturn(new CodWalletService.EarningRecord(earning, false));

        var body = controller.recordEarning(1L, 2L, new BigDecimal("33.00")).getBody();

        assertThat(body)
                .containsEntry("id", 7L)
                .containsEntry("status", "EARNED")
                .containsEntry("amount", new BigDecimal("33.00"));
    }

    @Test
    void getEarnings_mapsRowsWithBlankPaidAtWhenUnset() {
        RiderEarning unpaid = new RiderEarning();
        unpaid.setId(1L);
        unpaid.setAgentId(2L);
        unpaid.setOrderId(3L);
        unpaid.setAmount(BigDecimal.TEN);
        unpaid.setStatus("EARNED");
        RiderEarning paid = new RiderEarning();
        paid.setId(4L);
        paid.setAgentId(2L);
        paid.setOrderId(5L);
        paid.setAmount(BigDecimal.ONE);
        paid.setStatus("PAID");
        paid.setPaidAt(LocalDateTime.of(2026, 2, 2, 2, 2));
        when(codWalletService.earnings(2L)).thenReturn(List.of(unpaid, paid));

        List<Map<String, Object>> rows = controller.getEarnings(2L).getBody();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("paidAt", "");
        assertThat(rows.get(0)).containsEntry("status", "EARNED");
        assertThat(rows.get(1).get("paidAt")).isEqualTo(LocalDateTime.of(2026, 2, 2, 2, 2));
    }

    @Test
    void markEarningPaid_delegatesAndConfirms() {
        var body = controller.markEarningPaid(9L).getBody();

        verify(codWalletService).markEarningPaid(9L);
        assertThat(body)
                .containsEntry("id", 9L)
                .containsEntry("status", "PAID");
    }
}
