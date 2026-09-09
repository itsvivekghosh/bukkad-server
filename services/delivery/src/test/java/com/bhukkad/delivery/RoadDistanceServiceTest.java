package com.bhukkad.delivery;

import com.bhukkad.delivery.RoadDistanceService.RoadRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Graceful degradation ladder: OSRM when enabled and configured, haversine
 * otherwise or whenever the routing call misbehaves.
 */
@ExtendWith(MockitoExtension.class)
class RoadDistanceServiceTest {

    @Mock private RoadDistanceProperties properties;
    @Mock private OsrmClient osrmClient;

    @InjectMocks private RoadDistanceService service;

    private static final double BANGALORE_MG_TO_WHITEFIELD_KM = 15.6;

    @Test
    void route_disabledFallsBackToHaversineAtFallbackSpeed() {
        when(properties.isEnabled()).thenReturn(false);
        when(properties.getFallbackSpeedKmPerMin()).thenReturn(0.6);

        RoadRoute route = service.route(12.9716, 77.5946, 12.9698, 77.7500);

        verifyNoInteractions(osrmClient);
        assertThat(route.fromOsrm()).isFalse();
        assertThat(route.distanceKm()).isBetween(15.0, BANGALORE_MG_TO_WHITEFIELD_KM + 1.5);
        assertThat(route.durationMin()).isCloseTo(route.distanceKm() / 0.6, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void route_enabledReturnsOsrmResultWhenAvailable() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getOsrmUrl()).thenReturn("http://osrm.test");
        when(osrmClient.fetchRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new RoadRoute(20.4, 35.0, true)));

        RoadRoute route = service.route(12.9, 77.5, 13.0, 77.6);

        assertThat(route.fromOsrm()).isTrue();
        assertThat(route.distanceKm()).isEqualTo(20.4);
        assertThat(route.durationMin()).isEqualTo(35.0);
    }

    @Test
    void route_enabledButUrlBlankNeverCallsOsrm() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getOsrmUrl()).thenReturn("   ");
        when(properties.getFallbackSpeedKmPerMin()).thenReturn(0.6);

        RoadRoute route = service.route(12.9, 77.5, 12.9, 77.5);

        verifyNoInteractions(osrmClient);
        assertThat(route.fromOsrm()).isFalse();
        assertThat(route.distanceKm()).isZero();
    }

    @Test
    void route_osrmEmptyResultFallsBack() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getOsrmUrl()).thenReturn("http://osrm.test");
        when(properties.getFallbackSpeedKmPerMin()).thenReturn(0.6);
        when(osrmClient.fetchRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());

        assertThat(service.route(12.9716, 77.5946, 12.9698, 77.75).fromOsrm()).isFalse();
    }

    @Test
    void route_osrmExceptionFallsBack() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getOsrmUrl()).thenReturn("http://osrm.test");
        when(properties.getFallbackSpeedKmPerMin()).thenReturn(0.6);
        when(osrmClient.fetchRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("breaker open"));

        assertThat(service.route(12.9716, 77.5946, 12.9698, 77.75).fromOsrm()).isFalse();
    }

    @Test
    void isRoadRoutingActive_requiresFlagAndUrl() {
        when(properties.isEnabled()).thenReturn(false);
        assertThat(service.isRoadRoutingActive()).isFalse();

        when(properties.isEnabled()).thenReturn(true);
        when(properties.getOsrmUrl()).thenReturn("");
        assertThat(service.isRoadRoutingActive()).isFalse();

        when(properties.getOsrmUrl()).thenReturn("http://osrm.test");
        assertThat(service.isRoadRoutingActive()).isTrue();
    }
}
