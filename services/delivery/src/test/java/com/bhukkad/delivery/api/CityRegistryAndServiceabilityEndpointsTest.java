package com.bhukkad.delivery.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.api.controller.CityInternalController;
import com.bhukkad.delivery.api.controller.ServiceabilityController;
import com.bhukkad.delivery.domain.entity.CityConfig;
import com.bhukkad.delivery.domain.repository.CityConfigRepository;
import com.bhukkad.delivery.domain.entity.DeliveryZone;
import com.bhukkad.delivery.domain.repository.DeliveryZoneRepository;
import com.bhukkad.delivery.domain.entity.ZoneSurgeRule;
import com.bhukkad.delivery.domain.repository.ZoneSurgeRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CityRegistryAndServiceabilityEndpointsTest {

    @Mock private CityConfigRepository cityConfigRepository;
    @Mock private DeliveryZoneRepository zoneRepository;
    @Mock private ZoneSurgeRuleRepository surgeRepository;

    @InjectMocks private CityInternalController cityController;
    @InjectMocks private ServiceabilityController serviceabilityController;

    @Test
    void cityInternal_listsRegistry() {
        CityConfig city = new CityConfig();
        when(cityConfigRepository.findAll()).thenReturn(List.of(city));

        assertThat(cityController.cities()).containsExactly(city);
    }

    @Test
    void cityInternal_createRequiresTrimmableName() {
        assertThatThrownBy(() -> cityController.createCity(null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("name is required");
        assertThatThrownBy(() -> cityController.createCity(Map.of("name", " ")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cityInternal_create_echoesPersistenceRow() {
        when(cityConfigRepository.save(any(CityConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = cityController.createCity(Map.of("name", "  Pune "));

        ArgumentCaptor<CityConfig> saved = ArgumentCaptor.forClass(CityConfig.class);
        verify(cityConfigRepository).save(saved.capture());
        assertThat(saved.getValue().getCityName()).isEqualTo("Pune");
        assertThat(body)
                .containsEntry("name", "Pune")
                .containsEntry("currency", "INR")
                .containsEntry("timezone", "Asia/Kolkata");
    }

    @Test
    void serviceability_createZoneAndList() {
        when(zoneRepository.save(any(DeliveryZone.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryZone zone = serviceabilityController.createZone("Jayanagar");
        assertThat(zone.getName()).isEqualTo("Jayanagar");
        assertThat(serviceabilityController.zones()).isNotNull();
        verify(zoneRepository).findAll();
    }

    @Test
    void serviceability_surgeRequiresPositiveMultiplier() {
        assertThatThrownBy(() -> serviceabilityController.addSurge(
                1L, LocalTime.of(18, 0), LocalTime.of(22, 0), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("multiplier must be positive");
        assertThatThrownBy(() -> serviceabilityController.addSurge(
                1L, LocalTime.of(18, 0), LocalTime.of(22, 0), new BigDecimal("-1.5")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void serviceability_surgeRoundTrips() {
        when(surgeRepository.save(any(ZoneSurgeRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ZoneSurgeRule rule = serviceabilityController.addSurge(
                4L, LocalTime.of(18, 0), LocalTime.of(22, 0), new BigDecimal("1.25"));

        assertThat(rule.getZoneId()).isEqualTo(4L);
        assertThat(rule.getMultiplier()).isEqualByComparingTo("1.25");
        assertThat(rule.getStartTime()).isEqualTo(LocalTime.of(18, 0));

        List<ZoneSurgeRule> active = List.of(rule);
        when(surgeRepository.findByZoneIdAndActiveTrue(4L)).thenReturn(active);
        assertThat(serviceabilityController.activeSurge(4L)).isEqualTo(active);
    }

    // ------------------------------------------------------------------
    // Security annotation audits (HIGH-IDOR: mutations must be ADMIN-only)
    // ------------------------------------------------------------------

    @Test
    void serviceability_createZone_isAdminGated() throws Exception {
        PreAuthorize gate = findMethod(ServiceabilityController.class, "createZone", String.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(gate).as("createZone must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("ADMIN");
    }

    @Test
    void serviceability_addSurge_isAdminGated() throws Exception {
        PreAuthorize gate = findMethod(ServiceabilityController.class, "addSurge",
                Long.class, java.time.LocalTime.class, java.time.LocalTime.class, BigDecimal.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(gate).as("addSurge must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("ADMIN");
    }

    @Test
    void cityInternal_cities_isServiceOrAdminGated() throws Exception {
        PreAuthorize gate = findMethod(CityInternalController.class, "cities")
                .getAnnotation(PreAuthorize.class);
        assertThat(gate).as("cities must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("SERVICE").contains("ADMIN");
    }

    @Test
    void cityInternal_createCity_isServiceOrAdminGated() throws Exception {
        PreAuthorize gate = findMethod(CityInternalController.class, "createCity", Map.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(gate).as("createCity must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("SERVICE").contains("ADMIN");
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws Exception {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            throw new AssertionError("missing method " + name + " in " + clazz.getName());
        }
    }
}
