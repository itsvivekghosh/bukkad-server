package com.bhukkad.delivery;

import com.bhukkad.config.RoadDistanceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoadDistanceService} (P1-2): OSRM road routing when
 * configured, haversine fallback otherwise, and graceful degradation when the
 * OSRM call fails.
 */
class RoadDistanceServiceTest {

    private RoadDistanceProperties properties;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private RoadDistanceService service;

    @BeforeEach
    void setUp() {
        properties = new RoadDistanceProperties();
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        service = new RoadDistanceService(properties, restTemplate, objectMapper);
    }

    @Test
    void route_disabled_returnsHaversineFallback() {
        properties.setEnabled(false);

        RoadDistanceService.RoadRoute route = service.route(12.97, 77.59, 12.98, 77.60);

        assertThat(route.fromOsrm()).isFalse();
        // Haversine distance ~1.2-1.3 km between these points.
        assertThat(route.distanceKm()).isGreaterThan(0.5).isLessThan(3.0);
        assertThat(route.durationMin()).isGreaterThan(0);
    }

    @Test
    void route_osrmEnabled_returnsRoadRoute() throws Exception {
        properties.setEnabled(true);
        properties.setOsrmUrl("https://osrm.test");
        String osrmJson = "{\"routes\":[{\"distance\":2500,\"duration\":420}]}";
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(osrmJson);

        RoadDistanceService.RoadRoute route = service.route(12.97, 77.59, 12.98, 77.60);

        assertThat(route.fromOsrm()).isTrue();
        assertThat(route.distanceKm()).isEqualTo(2.5, within(0.001));
        assertThat(route.durationMin()).isEqualTo(7.0, within(0.001));
    }

    @Test
    void route_osrmCallFails_fallsBackToHaversine() {
        properties.setEnabled(true);
        properties.setOsrmUrl("https://osrm.test");
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("connection refused"));

        RoadDistanceService.RoadRoute route = service.route(12.97, 77.59, 12.98, 77.60);

        assertThat(route.fromOsrm()).isFalse();
        assertThat(route.distanceKm()).isGreaterThan(0);
    }

    @Test
    void route_osrmMalformedPayload_fallsBackToHaversine() {
        properties.setEnabled(true);
        properties.setOsrmUrl("https://osrm.test");
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("not-json");

        RoadDistanceService.RoadRoute route = service.route(12.97, 77.59, 12.98, 77.60);

        assertThat(route.fromOsrm()).isFalse();
    }

    @Test
    void route_osrmEmptyRoutes_fallsBackToHaversine() {
        properties.setEnabled(true);
        properties.setOsrmUrl("https://osrm.test");
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"code\":\"NoRoute\",\"routes\":[]}");

        RoadDistanceService.RoadRoute route = service.route(12.97, 77.59, 12.98, 77.60);

        assertThat(route.fromOsrm()).isFalse();
    }

    @Test
    void isRoadRoutingActive_returnsTrueOnlyWhenEnabledAndUrlConfigured() {
        assertThat(service.isRoadRoutingActive()).isFalse();

        properties.setEnabled(true);
        assertThat(service.isRoadRoutingActive()).isFalse();

        properties.setOsrmUrl("https://osrm.test");
        assertThat(service.isRoadRoutingActive()).isTrue();
    }
}
