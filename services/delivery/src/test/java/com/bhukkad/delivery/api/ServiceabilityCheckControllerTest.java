package com.bhukkad.delivery.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.domain.DeliveryZone;
import com.bhukkad.delivery.domain.DeliveryZoneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceabilityCheckControllerTest {

    @Mock private DeliveryZoneRepository zoneRepository;
    @InjectMocks private ServiceabilityCheckController controller;

    private static DeliveryZone zone(String name, boolean active) {
        DeliveryZone z = new DeliveryZone();
        z.setName(name);
        z.setIsActive(active);
        return z;
    }

    @Test
    void zoneNameCheck_isCaseInsensitive() {
        when(zoneRepository.findAll()).thenReturn(List.of(zone("Indiranagar", true)));

        Map<String, Object> ok = controller.serviceability(
                "indiranagar", null, null, null, null);
        Map<String, Object> no = controller.serviceability(
                "Koramangala", null, null, null, null);

        assertThat(ok).containsEntry("serviceable", true).containsEntry("zone", "indiranagar");
        assertThat(no).containsEntry("serviceable", false);
    }

    @Test
    void zoneSnapshotCache_servesWithinTtlWithoutReQuerying() {
        when(zoneRepository.findAll()).thenReturn(List.of(zone("A", true)));

        controller.zones();
        controller.zones();
        controller.serviceability("a", null, null, null, null);

        verify(zoneRepository, times(1)).findAll();
    }

    @Test
    void inActiveZonesNeverMatchByLegacyName() {
        when(zoneRepository.findAll()).thenReturn(List.of(zone("Ghost", false)));

        assertThat(controller.serviceability("ghost", null, null, null, null))
                .containsEntry("serviceable", false);
    }

    @Test
    void coordinateCheck_requiresFullTuple() {
        when(zoneRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> controller.serviceability(null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Provide zoneName");
        assertThatThrownBy(() -> controller.serviceability("  ", 1L, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void coordinateCheck_rejectsOutOfRange() {
        assertThatThrownBy(() -> controller.serviceability(null, 1L, -90.5, 0.0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid coordinates");
    }

    @Test
    void coordinateCheck_reportsAggregateZoneState() {
        when(zoneRepository.findAll()).thenReturn(List.of(zone("Live", true)));

        Map<String, Object> body = controller.serviceability(
                null, 7L, 12.9, 77.5, 250.0);

        assertThat(body)
                .containsEntry("serviceable", true)
                .containsEntry("restaurantId", 7L)
                .containsEntry("deliveryFee", 30.0)
                .containsEntry("etaMinutes", 35);
    }

    @Test
    void coordinateCheck_noActiveZone_costsNothing() {
        when(zoneRepository.findAll()).thenReturn(List.of(zone("Paused", false)));

        Map<String, Object> body = controller.serviceability(
                null, 7L, 12.9, 77.5, null);

        assertThat(body)
                .containsEntry("serviceable", false)
                .containsEntry("deliveryFee", 0.0)
                .containsEntry("etaMinutes", 0);
    }
}
