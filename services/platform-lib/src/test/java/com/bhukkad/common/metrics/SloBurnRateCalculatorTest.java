package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloBurnRateCalculatorTest {

    @Test
    void healthyTraffic_burnsBudgetAtConfiguredRate_andKeepsBudgetNearlyIntact() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SloProperties properties = properties();
        EndpointSloMetrics endpointMetrics = new EndpointSloMetrics(registry, properties);
        SloBurnRateCalculator calculator = new SloBurnRateCalculator(registry, properties);

        for (int request = 0; request < 994; request++) {
            endpointMetrics.record("GET", "/api/v1/orders", 100, 200);
        }
        for (int request = 0; request < 5; request++) {
            endpointMetrics.record("GET", "/api/v1/orders", 600, 200);
        }
        endpointMetrics.record("GET", "/api/v1/orders", 100, 500);

        assertNull(registry.find(SloBurnRateCalculator.BURN_RATE_METRIC).gauge());
        assertNull(registry.find(SloBurnRateCalculator.ERROR_BUDGET_REMAINING_METRIC).gauge());

        SloBurnRateCalculator.SloSnapshot snapshot = calculator.calculate();
        double oneHourOfAThirtyDayBudget = 1.0 / (30.0 * 24.0);

        assertEquals(1.0, snapshot.latencyBurnRate(), 0.0001);
        assertEquals(1.0, snapshot.availabilityBurnRate(), 0.0001);
        assertEquals(1.0 - oneHourOfAThirtyDayBudget,
                snapshot.latencyErrorBudgetRemaining(), 0.0001);
        assertEquals(1.0 - oneHourOfAThirtyDayBudget,
                snapshot.availabilityErrorBudgetRemaining(), 0.0001);
        assertEquals(snapshot.latencyBurnRate(),
                registry.get(SloBurnRateCalculator.BURN_RATE_METRIC)
                        .tag("slo", "latency")
                        .gauge()
                        .value());
        assertEquals(snapshot.availabilityErrorBudgetRemaining(),
                registry.get(SloBurnRateCalculator.ERROR_BUDGET_REMAINING_METRIC)
                        .tag("slo", "availability")
                        .gauge()
                        .value());
    }

    @Test
    void trafficExceedingSlo_burnRateExceedsOne_andRemainingBudgetDecreases() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SloProperties properties = properties();
        EndpointSloMetrics endpointMetrics = new EndpointSloMetrics(registry, properties);
        SloBurnRateCalculator calculator = new SloBurnRateCalculator(registry, properties);

        for (int request = 0; request < 900; request++) {
            endpointMetrics.record("GET", "/api/v1/orders", 100, 200);
        }
        for (int request = 0; request < 50; request++) {
            endpointMetrics.record("GET", "/api/v1/orders", 600, 200);
        }
        for (int request = 0; request < 50; request++) {
            endpointMetrics.record("GET", "/api/v1/orders", 100, 500);
        }

        SloBurnRateCalculator.SloSnapshot snapshot = calculator.calculate();

        assertTrue(snapshot.latencyBurnRate() > 1.0);
        assertTrue(snapshot.availabilityBurnRate() > 1.0);
        assertTrue(snapshot.latencyErrorBudgetRemaining() < 1.0);
        assertTrue(snapshot.availabilityErrorBudgetRemaining() < 1.0);
        assertTrue(snapshot.latencyErrorBudgetRemaining()
                < 1.0 - (1.0 / (30.0 * 24.0)));
        assertTrue(snapshot.availabilityErrorBudgetRemaining()
                < 1.0 - (1.0 / (30.0 * 24.0)));
    }

    @Test
    void zeroTraffic_doesNotDivideByZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SloProperties properties = properties();
        SloBurnRateCalculator calculator = new SloBurnRateCalculator(registry, properties);

        SloBurnRateCalculator.SloSnapshot snapshot = calculator.calculate();

        assertEquals(0.0, snapshot.latencyBurnRate());
        assertEquals(0.0, snapshot.availabilityBurnRate());
        assertEquals(1.0, snapshot.latencyErrorBudgetRemaining());
        assertEquals(1.0, snapshot.availabilityErrorBudgetRemaining());
        assertTrue(Double.isFinite(snapshot.latencyBurnRate()));
        assertTrue(Double.isFinite(snapshot.availabilityBurnRate()));
    }

    private SloProperties properties() {
        SloProperties properties = new SloProperties();
        properties.setEnabled(true);
        properties.setLatencyTargetMs(500);
        properties.setLatencyTargetPercent(99.5);
        properties.setAvailabilityTargetPercent(99.9);
        properties.setWindowMinutes(60);
        return properties;
    }
}
