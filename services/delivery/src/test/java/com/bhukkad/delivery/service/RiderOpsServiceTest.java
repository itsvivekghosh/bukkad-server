package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.domain.AgentCodWallet;
import com.bhukkad.delivery.domain.AgentCodWalletRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rider real-time ops: location pings, COD wallet, batch dispatch.
 */
@ExtendWith(MockitoExtension.class)
class RiderOpsServiceTest {

    @Mock private RiderLocationUpdateRepository locationRepository;
    @Mock private AgentCodWalletRepository codWalletRepository;
    @Mock private RiderDeliveryBatchRepository batchRepository;
    @Mock private RiderDeliveryBatchOrderRepository batchOrderRepository;
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
    void creditCod_existingWallet_accumulates() {
        AgentCodWallet wallet = new AgentCodWallet();
        wallet.setAgentId(7L);
        wallet.setBalance(new BigDecimal("100.00"));
        when(codWalletRepository.findByAgentId(7L)).thenReturn(Optional.of(wallet));
        when(codWalletRepository.save(any(AgentCodWallet.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentCodWallet result = service.creditCod(7L, new BigDecimal("50.00"));

        assertThat(result.getBalance()).isEqualByComparingTo("150.00");
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    void creditCod_newWallet_createsWithAmount() {
        when(codWalletRepository.findByAgentId(7L)).thenReturn(Optional.empty());
        when(codWalletRepository.save(any(AgentCodWallet.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentCodWallet result = service.creditCod(7L, new BigDecimal("200.00"));

        assertThat(result.getAgentId()).isEqualTo(7L);
        assertThat(result.getBalance()).isEqualByComparingTo("200.00");
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
    void createBatch_savesBatchAndLinksOrders() {
        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setId(3L);
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
