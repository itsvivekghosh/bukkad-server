package com.bhukkad.metrics;

import com.bhukkad.live.OrderSseStreamService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OrderMetrics metrics = new OrderMetrics(registry);

    @Test void orderCreated_increments() {
        metrics.orderCreated();
        assertEquals(1.0, registry.counter("bhukkad.orders.created").count());
    }

    @Test void orderCancelled_increments() {
        metrics.orderCancelled();
        metrics.orderCancelled();
        assertEquals(2.0, registry.counter("bhukkad.orders.cancelled").count());
    }

    @Test void orderDelivered_increments() {
        metrics.orderDelivered();
        assertEquals(1.0, registry.counter("bhukkad.orders.delivered").count());
    }

    @Test void countersAreIndependent() {
        metrics.orderCreated();
        metrics.orderDelivered();
        assertEquals(1.0, registry.counter("bhukkad.orders.created").count());
        assertEquals(1.0, registry.counter("bhukkad.orders.delivered").count());
        assertEquals(0.0, registry.counter("bhukkad.orders.cancelled").count());
    }
}
