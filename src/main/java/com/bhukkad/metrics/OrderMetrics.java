package com.bhukkad.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OrderMetrics {

    private final Counter ordersCreated;
    private final Counter ordersCancelled;
    private final Counter ordersDelivered;

    public OrderMetrics(MeterRegistry registry) {
        ordersCreated = Counter.builder("bhukkad.orders.created")
                .description("Total orders placed")
                .register(registry);
        ordersCancelled = Counter.builder("bhukkad.orders.cancelled")
                .description("Total orders cancelled")
                .register(registry);
        ordersDelivered = Counter.builder("bhukkad.orders.delivered")
                .description("Total orders delivered")
                .register(registry);
    }

    public void orderCreated() {
        ordersCreated.increment();
    }

    public void orderCancelled() {
        ordersCancelled.increment();
    }

    public void orderDelivered() {
        ordersDelivered.increment();
    }
}
