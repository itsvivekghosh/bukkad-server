package com.bhukkad.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessMetricsTest {

    private MeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BusinessMetrics(registry);
    }

    @Test void search_incrementsCounter() {
        metrics.search();
        assertEquals(1.0, registry.counter("bhukkad.funnel.search").count());
    }

    @Test void menuView_incrementsCounter() {
        metrics.menuView();
        assertEquals(1.0, registry.counter("bhukkad.funnel.menu_view").count());
    }

    @Test void cartAdd_incrementsCounter() {
        metrics.cartAdd();
        assertEquals(1.0, registry.counter("bhukkad.funnel.cart_add").count());
    }

    @Test void checkout_incrementsCounter() {
        metrics.checkout();
        assertEquals(1.0, registry.counter("bhukkad.funnel.checkout").count());
    }

    @Test void payment_incrementsCounter() {
        metrics.payment();
        assertEquals(1.0, registry.counter("bhukkad.funnel.payment").count());
    }

    @Test void delivered_incrementsCounter() {
        metrics.delivered();
        assertEquals(1.0, registry.counter("bhukkad.funnel.delivered").count());
    }

    @Test void countersAreIndependent() {
        metrics.search();
        metrics.search();
        metrics.payment();
        assertEquals(2.0, registry.counter("bhukkad.funnel.search").count());
        assertEquals(1.0, registry.counter("bhukkad.funnel.payment").count());
        assertEquals(0.0, registry.counter("bhukkad.funnel.delivered").count());
    }

    @Test void allCountersRegisteredUpfront() {
        // All six funnel counters must be registered at construction (even with zero events).
        assertEquals(0.0, registry.counter("bhukkad.funnel.search").count());
        assertEquals(0.0, registry.counter("bhukkad.funnel.menu_view").count());
        assertEquals(0.0, registry.counter("bhukkad.funnel.cart_add").count());
        assertEquals(0.0, registry.counter("bhukkad.funnel.checkout").count());
        assertEquals(0.0, registry.counter("bhukkad.funnel.payment").count());
        assertEquals(0.0, registry.counter("bhukkad.funnel.delivered").count());
    }
}
