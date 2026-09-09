package com.bhukkad.delivery;

import com.bhukkad.delivery.RoadDistanceService.RoadRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OSRM route parsing: contract on the routes[0] payload, empty option for
 * unreachable/zero results, hard errors rethrown for the circuit breaker,
 * and the declared fallback hooks returning empty.
 */
@ExtendWith(MockitoExtension.class)
class OsrmClientTest {

    @Mock private RestTemplate restTemplate;

    private RoadDistanceProperties properties() {
        RoadDistanceProperties properties = new RoadDistanceProperties();
        properties.setEnabled(true);
        properties.setOsrmUrl("http://osrm.test");
        return properties;
    }

    private OsrmClient client() {
        return new OsrmClient(properties(), restTemplate, new ObjectMapper());
    }

    @Test
    void fetchRoute_parsesDistanceAndDurationIntoMinutes() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"code\":\"Ok\",\"routes\":[{\"distance\":2500.0,\"duration\":600.0}]}");

        Optional<RoadRoute> route = client().fetchRoute(12.9, 77.5, 12.97, 77.6);

        assertThat(route).isPresent();
        assertThat(route.get().distanceKm()).isEqualTo(2.5);
        assertThat(route.get().durationMin()).isEqualTo(10.0);
        assertThat(route.get().fromOsrm()).isTrue();
    }

    @Test
    void fetchRoute_buildsUrlFromCoordinates() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"routes\":[{\"distance\":1000.0,\"duration\":120.0}]}");

        client().fetchRoute(12.9, 77.5, 13.0, 77.6);

        verify(restTemplate).getForObject(eq(
                "http://osrm.test/route/v1/driving/77.5,12.9;77.6,13.0?overview=false&steps=false"),
                eq(String.class));
    }

    @Test
    void fetchRoute_nullBodyYieldsEmpty() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(null);

        assertThat(client().fetchRoute(1, 1, 2, 2)).isEmpty();
    }

    @Test
    void fetchRoute_missingRoutesYieldsEmpty() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"code\":\"NoRoute\",\"routes\":[]}");

        assertThat(client().fetchRoute(1, 1, 2, 2)).isEmpty();
    }

    @Test
    void fetchRoute_zeroDistanceYieldsEmpty() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"routes\":[{\"distance\":0,\"duration\":0}]}");

        assertThat(client().fetchRoute(1, 1, 2, 2)).isEmpty();
    }

    @Test
    void fetchRoute_httpFailureRethrowsForCircuitBreaker() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect refused"));

        assertThatThrownBy(() -> client().fetchRoute(1, 1, 2, 2))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void fetchRoute_malformedPayloadRethrows() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("{\"routes\":");

        assertThatThrownBy(() -> client().fetchRoute(1, 1, 2, 2))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void fallbackHooks_returnEmptyOptional() {
        OsrmClient client = client();

        assertThat(client.osrmFallback(1, 1, 2, 2, new RuntimeException("open")))
                .isEmpty();
        assertThat(client.osrmTimeoutFallback(1, 1, 2, 2,
                new java.util.concurrent.TimeoutException("slow"))).isEmpty();
    }
}
