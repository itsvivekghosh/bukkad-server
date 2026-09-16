package com.bhukkad.delivery;

import com.bhukkad.delivery.config.RoadDistanceProperties;
import com.bhukkad.delivery.infrastructure.client.OsrmClient;
import com.bhukkad.delivery.infrastructure.client.RoadDistanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Validates OSRM client metrics emission and configuration.
 *
 * <p>Uses SimpleMeterRegistry to verify metrics are emitted correctly
 * without requiring a full Micrometer infrastructure.</p>
 */
@ExtendWith(MockitoExtension.class)
class OsrmMetricsValidationTest {

    @Mock
    private RestTemplate restTemplate;

    private OsrmClient client;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        RoadDistanceProperties properties = new RoadDistanceProperties();
        properties.setEnabled(true);
        properties.setOsrmUrl("http://osrm.test");
        client = new OsrmClient(properties, restTemplate, new ObjectMapper(), meterRegistry);
    }

    @Test
    void fetchRoute_emitsSuccessMetrics() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"code\":\"Ok\",\"routes\":[{\"distance\":2500.0,\"duration\":600.0}]}");

        Optional<RoadDistanceService.RoadRoute> route = client.fetchRoute(12.9, 77.5, 12.97, 77.6);

        assertThat(route).isPresent();
        // Verify at least one metric was emitted
        assertThat(meterRegistry.getMeters()).hasSizeGreaterThan(0);
        assertThat(meterRegistry.find("osrm.requests").tag("result", "ok").counter().count()).isEqualTo(1);
    }

    @Test
    void fetchRoute_emitsErrorMetricsOnFailure() {
        // RestTemplate.getForObject wraps IOException in ResourceAccessException
        java.net.SocketTimeoutException timeout = new java.net.SocketTimeoutException("timeout");
        org.springframework.web.client.ResourceAccessException wrapped = new org.springframework.web.client.ResourceAccessException("timeout", timeout);
        doThrow(wrapped).when(restTemplate).getForObject(anyString(), eq(String.class));

        assertThatThrownBy(() -> client.fetchRoute(1, 1, 2, 2))
                .isInstanceOf(RuntimeException.class);

        // Verify error metrics were emitted
        assertThat(meterRegistry.getMeters()).hasSizeGreaterThan(0);
        assertThat(meterRegistry.find("osrm.requests")
                .tag("result", "error").counter().count()).isEqualTo(1);
    }

    @Test
    void fetchRoute_emitsEmptyMetricOnNullBody() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(null);

        Optional<RoadDistanceService.RoadRoute> route = client.fetchRoute(1, 1, 2, 2);

        assertThat(route).isEmpty();
        assertThat(meterRegistry.find("osrm.requests")
                .tag("result", "empty").counter().count()).isEqualTo(1);
    }

    @Test
    void fetchRoute_emitsMissingMetricOnNoRoute() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"code\":\"NoRoute\",\"routes\":[]}");

        Optional<RoadDistanceService.RoadRoute> route = client.fetchRoute(1, 1, 2, 2);

        assertThat(route).isEmpty();
        assertThat(meterRegistry.find("osrm.requests")
                .tag("result", "missing").counter().count()).isEqualTo(1);
    }
}
