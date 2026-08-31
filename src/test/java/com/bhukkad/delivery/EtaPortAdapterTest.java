package com.bhukkad.delivery;

import com.bhukkad.delivery.api.EtaPort;
import com.bhukkad.delivery.api.EtaPort.EtaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the delivery-domain ETA seam consumed by the live module.
 */
@ExtendWith(MockitoExtension.class)
class EtaPortAdapterTest {

    @Mock
    private OrderEtaService orderEtaService;

    @InjectMocks
    private EtaPortAdapter adapter;

    @Test
    void computeEta_delegatesToEtaService() {
        LocalDateTime etaAt = LocalDateTime.of(2026, 8, 29, 13, 0);
        when(orderEtaService.computeLiveEtaForOrder(42L)).thenReturn(Optional.of(
                new EtaSnapshot(18, etaAt, 12, 24, 1.2, 1.0, "status=OUT_FOR_DELIVERY")));

        Optional<EtaSnapshot> result = adapter.computeEta(42L);

        assertTrue(result.isPresent());
        assertEquals(18, result.get().minutes());
        assertEquals(etaAt, result.get().etaAt());
        assertEquals(12, result.get().minMinutes());
        assertEquals(24, result.get().maxMinutes());
        assertEquals(1.2, result.get().trafficFactor());
        assertEquals(1.0, result.get().surgeMultiplier());
        assertEquals("status=OUT_FOR_DELIVERY", result.get().factors());
    }

    @Test
    void computeEta_emptyWhenOrderMissing() {
        when(orderEtaService.computeLiveEtaForOrder(99L)).thenReturn(Optional.empty());

        assertTrue(adapter.computeEta(99L).isEmpty());
    }

    @Test
    void adapterImplementsPort() {
        assertTrue(adapter instanceof EtaPort);
    }
}
