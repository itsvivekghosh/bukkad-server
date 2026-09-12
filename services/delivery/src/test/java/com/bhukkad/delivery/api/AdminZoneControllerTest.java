package com.bhukkad.delivery.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.delivery.domain.CityConfig;
import com.bhukkad.delivery.domain.CityConfigRepository;
import com.bhukkad.delivery.domain.DeliveryZone;
import com.bhukkad.delivery.domain.DeliveryZoneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminZoneControllerTest {

    @Mock private DeliveryZoneRepository zoneRepository;
    @Mock private CityConfigRepository cityConfigRepository;
    @InjectMocks private AdminZoneController controller;

    @Test
    void zones_delegatesToRepository() {
        DeliveryZone zone = new DeliveryZone();
        when(zoneRepository.findAll()).thenReturn(List.of(zone));

        assertThat(controller.zones()).containsExactly(zone);
    }

    @Test
    void createZone_nullBody_rejected() {
        assertThatThrownBy(() -> controller.createZone(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Zone body is required");
    }

    @Test
    void createZone_missingNameOnNewZone_rejected() {
        assertThatThrownBy(() -> controller.createZone(
                new AdminZoneController.ZoneUpsertRequest(" ", 1.0, 2.0, 3.0, 25, 30.0, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createZone_trimsNameAndCarriesActiveFlag() {
        when(zoneRepository.save(any(DeliveryZone.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryZone zone = controller.createZone(new AdminZoneController.ZoneUpsertRequest(
                "  Indiranagar ", 12.97, 77.6, 4.0, 30, 35.0, false));

        assertThat(zone.getName()).isEqualTo("Indiranagar");
        assertThat(zone.getIsActive()).isFalse();
    }

    @Test
    void updateZone_keepsExistingNameWhenRequestOmitsIt() {
        DeliveryZone stored = new DeliveryZone();
        stored.setName("Koramangala");
        when(zoneRepository.findById(1L)).thenReturn(Optional.of(stored));
        when(zoneRepository.save(any(DeliveryZone.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryZone updated = controller.updateZone(1L,
                new AdminZoneController.ZoneUpsertRequest(null, null, null, null, null, null, true));

        assertThat(updated.getName()).isEqualTo("Koramangala");
        assertThat(updated.getIsActive()).isTrue();
    }

    @Test
    void updateZone_missing_throws404() {
        when(zoneRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.updateZone(9L, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Zone not found");
    }

    @Test
    void deleteZone_roundTripAndMissing() {
        DeliveryZone stored = new DeliveryZone();
        when(zoneRepository.findById(3L)).thenReturn(Optional.of(stored));

        assertThat(controller.deleteZone(3L)).containsEntry("id", 3L);
        verify(zoneRepository).delete(stored);

        when(zoneRepository.findById(4L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.deleteZone(4L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cities_delegates() {
        CityConfig city = new CityConfig();
        when(cityConfigRepository.findAll()).thenReturn(List.of(city));

        assertThat(controller.cities()).containsExactly(city);
    }

    @Test
    void createCity_requiresNameThenEchoesRegistryShape() {
        assertThatThrownBy(() -> controller.createCity(Map.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("city is required");
        assertThatThrownBy(() -> controller.createCity(Map.of("city", "  ")))
                .isInstanceOf(BusinessException.class);

        when(cityConfigRepository.save(any(CityConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = controller.createCity(Map.of("city", "  Bengaluru "));

        ArgumentCaptor<CityConfig> saved = ArgumentCaptor.forClass(CityConfig.class);
        verify(cityConfigRepository).save(saved.capture());
        assertThat(saved.getValue().getCityName()).isEqualTo("Bengaluru");
        assertThat(body)
                .containsEntry("city", "Bengaluru")
                .containsEntry("displayName", "Bengaluru")
                .containsEntry("currency", "INR")
                .containsEntry("timezone", "Asia/Kolkata")
                .containsEntry("isServiceable", true);
    }

    @Test
    void updateCity_missingThrows_otherwiseRenames() {
        when(cityConfigRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.updateCity(1L, Map.of("city", "x")))
                .isInstanceOf(ResourceNotFoundException.class);

        CityConfig city = new CityConfig();
        city.setCityName("old");
        when(cityConfigRepository.findById(2L)).thenReturn(Optional.of(city));
        when(cityConfigRepository.save(any(CityConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(controller.updateCity(2L, Map.of("city", "new")).get("city"))
                .isEqualTo("new");
    }

    @Test
    void deleteCity_roundTripAndMissing() {
        CityConfig city = new CityConfig();
        when(cityConfigRepository.findById(5L)).thenReturn(Optional.of(city));

        assertThat(controller.deleteCity(5L)).containsEntry("message", "City deleted");
        verify(cityConfigRepository).delete(city);

        when(cityConfigRepository.findById(6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.deleteCity(6L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cityDefaults_mintsNamedCity() {
        CityConfig city = AdminZoneController.CityDefaults.newCity("Mysuru");

        assertThat(city.getCityName()).isEqualTo("Mysuru");
        assertThat(city.getCreatedAt()).isNotNull();
    }
}
