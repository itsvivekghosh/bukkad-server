package com.bhukkad.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointSloMetricsTest {

    @Test
    void record_createsTimerAndErrorsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        EndpointSloMetrics metrics = new EndpointSloMetrics(registry);

        metrics.record("GET", "/api/v1/orders/customer/123", 200, 200);
        metrics.record("GET", "/api/v1/orders/customer/456", 500, 500);

        double count = registry.get("bhukkad.http.requests")
                .tag("uri", "/api/v1/orders/customer/{id}")
                .tag("method", "GET")
                .timer().count();
        assertEquals(2.0, count);

        double errors = registry.get("bhukkad.http.errors")
                .tag("uri", "/api/v1/orders/customer/{id}")
                .tag("method", "GET")
                .counter().count();
        assertEquals(1.0, errors);
    }

    @Test
    void normalizeUri_collapsesNumericSegments() {
        assertEquals("/api/v1/orders/customer/{id}",
                EndpointSloMetrics.normalizeUri("/api/v1/orders/customer/123"));
        assertEquals("/api/v1/restaurants/public",
                EndpointSloMetrics.normalizeUri("/api/v1/restaurants/public"));
        assertEquals("/", EndpointSloMetrics.normalizeUri(null));
        assertEquals("/", EndpointSloMetrics.normalizeUri(""));
    }
}
