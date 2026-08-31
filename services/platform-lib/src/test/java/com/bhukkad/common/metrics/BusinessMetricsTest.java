package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);

    @Test
    void increment_registersCounter() {
        metrics.increment("orders.created", "status", "settled");
        metrics.increment("orders.created", "status", "settled");
        assertThat(registry.counter("orders.created", "status", "settled").count()).isEqualTo(2);
    }

    @Test
    void timed_registersTimerAndReturnsResult() {
        String result = metrics.timed("payment.duration", () -> "ok");
        assertThat(result).isEqualTo("ok");
        assertThat(registry.timer("payment.duration").count()).isEqualTo(1);
    }

    @Test
    void record_registersTimer() {
        metrics.record("search.duration", 25);
        assertThat(registry.timer("search.duration").count()).isEqualTo(1);
    }
}
