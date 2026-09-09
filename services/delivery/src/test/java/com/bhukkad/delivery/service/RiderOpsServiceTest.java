package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.RiderEarningsProperties;
import com.bhukkad.delivery.client.PaymentServiceClient;
import com.bhukkad.delivery.domain.RiderDeliveryBatch;
import com.bhukkad.delivery.domain.RiderDeliveryBatchOrderRepository;
import com.bhukkad.delivery.domain.RiderDeliveryBatchRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rider real-time ops: location pings, COD wallet, batch dispatch.
 * COD wallet operations are delegated to payment service.
 */
@ExtendWith(MockitoExtension.class)
class RiderOpsServiceTest {

    @Mock private RiderLocationUpdateRepository locationRepository;
    @Mock private RiderDeliveryBatchRepository batchRepository;
    @Mock private RiderDeliveryBatchOrderRepository batchOrderRepository;
    @Mock private PaymentServiceClient paymentClient;
    @Mock private RiderEarningsProperties earningsProperties;
    @InjectMocks private RiderOpsService service;

    @Test
    void reportLocation_savesUpdate() {
        when(locationRepository.save(any(RiderLocationUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        RiderLocationUpdate update = service.reportLocation(7L, 12.9716, 77.5946);

        assertThat(update.getAgentId()).isEqualTo(7L);
        assertThat(update.getLatitude()).isEqualTo(12.9716);
        assertThat(update.getLongitude()).isEqualTo(77.5946);
        assertThat(update.getRecordedAt()).isNotNull();
    }

    @Test
    void recentLocations_returnsAgentPings() {
        when(locationRepository.findByAgentId(7L)).thenReturn(List.of(new RiderLocationUpdate()));

        assertThat(service.recentLocations(7L)).hasSize(1);
    }

    @Test
    void creditCod_delegatesToPaymentService() {
        Map<String, Object> expectedResponse = Map.of(
                "agentId", 7L,
                "balance", new BigDecimal("150.00"),
                "credited", new BigDecimal("50.00")
        );
        when(paymentClient.creditCodWallet(eq(7L), eq(new BigDecimal("50.00"))))
                .thenReturn(expectedResponse);

        Map<String, Object> result = service.creditCod(7L, new BigDecimal("50.00"));

        assertThat(result.get("balance")).isEqualTo(new BigDecimal("150.00"));
        verify(paymentClient).creditCodWallet(7L, new BigDecimal("50.00"));
    }

    @Test
    void getCodWallet_delegatesToPaymentService() {
        Map<String, Object> expectedResponse = Map.of(
                "agentId", 7L,
                "balance", new BigDecimal("100.00")
        );
        when(paymentClient.getCodWallet(7L)).thenReturn(expectedResponse);

        Map<String, Object> result = service.getCodWallet(7L);

        assertThat(result.get("balance")).isEqualTo(new BigDecimal("100.00"));
        verify(paymentClient).getCodWallet(7L);
    }

    @Test
    void creditCod_nonPositive_throws() {
        assertThatThrownBy(() -> service.creditCod(7L, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> service.creditCod(7L, new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void debitCod_delegatesToPaymentService() {
        when(paymentClient.debitCodWallet(eq(7L), eq(new BigDecimal("25.00"))))
                .thenReturn(Map.of("agentId", 7L, "balance", new BigDecimal("75.00")));

        Map<String, Object> result = service.debitCod(7L, new BigDecimal("25.00"));

        assertThat(result.get("balance")).isEqualTo(new BigDecimal("75.00"));
        verify(paymentClient).debitCodWallet(7L, new BigDecimal("25.00"));
    }

    @Test
    void debitCod_nonPositive_throws() {
        assertThatThrownBy(() -> service.debitCod(7L, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> service.debitCod(7L, new BigDecimal("-5")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void recordEarning_usesDefaultRate() {
        when(earningsProperties.getPerDelivery()).thenReturn(30.0);
        when(paymentClient.recordEarning(eq(7L), eq(42L), eq(new BigDecimal("30.0"))))
                .thenReturn(Map.of("id", 99L, "agentId", 7L, "amount", new BigDecimal("30.0")));

        Map<String, Object> result = service.recordEarning(7L, 42L);

        assertThat(result.get("agentId")).isEqualTo(7L);
        verify(earningsProperties).getPerDelivery();
        verify(paymentClient).recordEarning(7L, 42L, new BigDecimal("30.0"));
    }

    @Test
    void getEarnings_delegatesToPaymentService() {
        when(paymentClient.getEarnings(7L))
                .thenReturn(List.of(Map.of("id", 99L, "amount", new BigDecimal("30.0"))));

        List<Map<String, Object>> earnings = service.getEarnings(7L);

        assertThat(earnings).hasSize(1);
        verify(paymentClient).getEarnings(7L);
    }

    @Test
    void markEarningPaid_delegatesToPaymentService() {
        when(paymentClient.markEarningPaid(99L))
                .thenReturn(Map.of("id", 99L, "status", "PAID"));

        Map<String, Object> result = service.markEarningPaid(99L);

        assertThat(result.get("status")).isEqualTo("PAID");
        verify(paymentClient).markEarningPaid(99L);
    }

    @Test
    void createBatch_savesBatchAndLinksOrders() {
        when(batchRepository.save(any(RiderDeliveryBatch.class))).thenAnswer(inv -> {
            RiderDeliveryBatch b = inv.getArgument(0);
            b.setId(3L);
            return b;
        });

        RiderDeliveryBatch result = service.createBatch(7L, List.of(11L, 12L));

        assertThat(result.getAgentId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo("ASSIGNED");
        assertThat(result.getId()).isEqualTo(3L);
        verify(batchOrderRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void createBatch_emptyOrNullOrders_throws() {
        assertThatThrownBy(() -> service.createBatch(7L, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one order");
        assertThatThrownBy(() -> service.createBatch(7L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one order");
    }
}
