package com.bhukkad.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

@Service
public class BusinessMetricsService {

    private final MeterRegistry registry;
    private final Counter ordersCreated;
    private final Counter gmv;
    private final Counter ordersCancelled;
    private final Counter settlements;
    private final DoubleAdder gmvTotal = new DoubleAdder();
    private final LongAdder orderCount = new LongAdder();

    public BusinessMetricsService(MeterRegistry registry) {
        this.registry = registry;
        if (registry != null) {
            this.ordersCreated = Counter.builder("bhukkad.orders.created")
                    .description("Total orders placed").register(registry);
            this.gmv = Counter.builder("bhukkad.gmv")
                    .description("Gross merchandise value").register(registry);
            this.ordersCancelled = Counter.builder("bhukkad.orders.cancelled")
                    .description("Total orders cancelled").register(registry);
            this.settlements = Counter.builder("bhukkad.settlements.amount")
                    .description("Settlement throughput amount").register(registry);
            Gauge.builder("bhukkad.aov", this::currentAov)
                    .description("Average order value").register(registry);
        } else {
            this.ordersCreated = null;
            this.gmv = null;
            this.ordersCancelled = null;
            this.settlements = null;
        }
    }

    public void recordOrderCreated(double totalAmount) {
        if (registry == null) return;
        ordersCreated.increment();
        gmv.increment(totalAmount);
        gmvTotal.add(totalAmount);
        orderCount.increment();
    }

    public void recordOrderCancelled() {
        if (registry == null) return;
        ordersCancelled.increment();
    }

    public void recordSettlement(double amount) {
        if (registry == null) return;
        settlements.increment(amount);
    }

    private double currentAov() {
        long count = orderCount.sum();
        return count == 0 ? 0.0 : gmvTotal.sum() / count;
    }
}