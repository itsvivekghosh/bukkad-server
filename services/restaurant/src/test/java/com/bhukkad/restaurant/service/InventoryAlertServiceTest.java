package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.InventoryAlert;
import com.bhukkad.restaurant.domain.InventoryAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Low-stock alerting: raises alerts for out-of-stock and low-stock items.
 */
@ExtendWith(MockitoExtension.class)
class InventoryAlertServiceTest {

    @Mock private InventoryAlertRepository alertRepository;
    @InjectMocks private InventoryAlertService service;

    @Test
    void raise_zeroStock_isOutOfStock() {
        when(alertRepository.save(any(InventoryAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryAlert alert = service.raise(42L, 0, 10);

        assertThat(alert.getMenuItemId()).isEqualTo(42L);
        assertThat(alert.getCurrentStock()).isEqualTo(0);
        assertThat(alert.getThreshold()).isEqualTo(10);
        assertThat(alert.getAlertType()).isEqualTo(InventoryAlert.TYPE_OUT_OF_STOCK);
    }

    @Test
    void raise_positiveStockBelowThreshold_isLowStock() {
        when(alertRepository.save(any(InventoryAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryAlert alert = service.raise(42L, 5, 10);

        assertThat(alert.getCurrentStock()).isEqualTo(5);
        assertThat(alert.getAlertType()).isEqualTo(InventoryAlert.TYPE_LOW_STOCK);
    }

    @Test
    void raise_negativeStock_isOutOfStock() {
        when(alertRepository.save(any(InventoryAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryAlert alert = service.raise(42L, -1, 10);

        assertThat(alert.getAlertType()).isEqualTo(InventoryAlert.TYPE_OUT_OF_STOCK);
    }

    @Test
    void recent_returnsAlertsForMenuItem() {
        when(alertRepository.findByMenuItemId(42L)).thenReturn(List.of(new InventoryAlert()));

        assertThat(service.recent(42L)).hasSize(1);
    }
}