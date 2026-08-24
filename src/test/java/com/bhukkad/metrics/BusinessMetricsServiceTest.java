package com.bhukkad.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessMetricsServiceTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetricsService service = new BusinessMetricsService(registry);

    @Test
    void recordOrderCreated_incrementsCountersAndGmv() {
        service.recordOrderCreated(250.50);

        assertEquals(1.0, registry.counter("bhukkad.orders.created").count(), 0.001);
        assertEquals(250.50, registry.counter("bhukkad.gmv").count(), 0.001);
    }

    @Test
    void recordOrderCreated_updatesAovGauge() {
        service.recordOrderCreated(100.0);
        service.recordOrderCreated(200.0);

        double aov = registry.get("bhukkad.aov").gauge().value();
        assertEquals(150.0, aov, 0.001);
    }

    @Test
    void recordOrderCreated_aovIsZeroWhenNoOrders() {
        double aov = registry.get("bhukkad.aov").gauge().value();
        assertEquals(0.0, aov, 0.001);
    }

    @Test
    void recordOrderCancelled_incrementsCounter() {
        service.recordOrderCancelled();
        assertEquals(1.0, registry.counter("bhukkad.orders.cancelled").count(), 0.001);
    }

    @Test
    void recordSettlement_incrementsByAmount() {
        service.recordSettlement(1000.0);
        assertEquals(1000.0, registry.counter("bhukkad.settlements.amount").count(), 0.001);
    }

    @Test
    void nullRegistryDoesNotThrow() {
        BusinessMetricsService nullService = new BusinessMetricsService(null);
        nullService.recordOrderCreated(100.0);
        nullService.recordOrderCancelled();
        nullService.recordSettlement(500.0);
    }
}