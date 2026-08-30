package com.bhukkad.delivery;

import com.bhukkad.config.RoadDistanceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OsrmClientTest {

    private RoadDistanceProperties properties;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private OsrmClient osrmClient;

    @BeforeEach
    void setUp() {
        properties = new RoadDistanceProperties();
        properties.setEnabled(true);
        properties.setOsrmUrl("https://osrm.test");
        properties.setConnectTimeoutMs(2000);
        properties.setReadTimeoutMs(2000);
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        osrmClient = new OsrmClient(properties, restTemplate, objectMapper);
    }

    @Test
    void fetchRoute_success_returnsRoadRoute() {
        String json = "{\"routes\":[{\"distance\":3200,\"duration\":420}]}";
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(json);

        Optional<RoadDistanceService.RoadRoute> result = osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60);

        assertThat(result).isPresent();
        assertThat(result.get().distanceKm()).isEqualTo(3.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.get().durationMin()).isEqualTo(7.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.get().fromOsrm()).isTrue();
    }

    @Test
    void fetchRoute_missingRoutes_returnsEmpty() {
        String json = "{\"code\":\"NoRoute\",\"routes\":[]}";
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(json);

        Optional<RoadDistanceService.RoadRoute> result = osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60);

        assertThat(result).isEmpty();
    }

    @Test
    void fetchRoute_zeroDistance_returnsEmpty() {
        String json = "{\"routes\":[{\"distance\":0,\"duration\":0}]}";
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(json);

        Optional<RoadDistanceService.RoadRoute> result = osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60);

        assertThat(result).isEmpty();
    }

    @Test
    void fetchRoute_nullBody_returnsEmpty() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(null);

        Optional<RoadDistanceService.RoadRoute> result = osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60);

        assertThat(result).isEmpty();
    }

    @Test
    void fetchRoute_restClientException_throwsForRetry() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));

        assertThatThrownBy(() -> osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RestClientException.class);
    }

    @Test
    void fetchRoute_malformedJson_throwsRuntimeException() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("not-json");

        assertThatThrownBy(() -> osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void osrmFallback_returnsEmpty() {
        Optional<RoadDistanceService.RoadRoute> result =
                osrmClient.osrmFallback(12.97, 77.59, 12.98, 77.60, new RuntimeException("circuit open"));
        assertThat(result).isEmpty();
    }

    @Test
    void osrmClient_hasCircuitBreakerAndBulkheadAnnotations() throws NoSuchMethodException {
        var method = OsrmClient.class.getMethod("fetchRoute", double.class, double.class, double.class, double.class);
        assertThat(method.isAnnotationPresent(io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker.class)).isTrue();
        assertThat(method.isAnnotationPresent(io.github.resilience4j.bulkhead.annotation.Bulkhead.class)).isTrue();
        assertThat(method.isAnnotationPresent(io.github.resilience4j.retry.annotation.Retry.class)).isTrue();

        var cb = method.getAnnotation(io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker.class);
        assertThat(cb.name()).isEqualTo("osrm");
        var bh = method.getAnnotation(io.github.resilience4j.bulkhead.annotation.Bulkhead.class);
        assertThat(bh.name()).isEqualTo("osrm");
    }

    // ===== Batch D: timeout fallback + unexpected-exception branch =====

    @Test
    void osrmTimeoutFallback_returnsEmptyRoute() {
        Optional<RoadDistanceService.RoadRoute> result = osrmClient.osrmTimeoutFallback(
                12.97, 77.59, 12.98, 77.60, new java.util.concurrent.TimeoutException("osrm timed out"));

        assertThat(result).isEmpty();
    }

    @Test
    void fetchRoute_unexpectedException_throwsForFallback() {
        // IllegalStateException is neither RestClientException, nor Jackson, nor
        // IOException — exercises the generic error branch.
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        assertThatThrownBy(() -> osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void fetchRoute_ioException_throwsForFallback() {
        // A raw IOException from the transport layer hits the dedicated
        // IOException branch (rethrown so Retry can schedule another attempt).
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenAnswer(inv -> { throw new java.io.IOException("socket reset"); });

        assertThatThrownBy(() -> osrmClient.fetchRoute(12.97, 77.59, 12.98, 77.60))
                .isInstanceOf(RuntimeException.class);
    }
}
