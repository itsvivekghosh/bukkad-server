package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Convenience wrapper around Micrometer for platform-level business metrics
 * (port of {@code com.bhukkad.metrics.BusinessMetrics}).
 */
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void increment(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    public void record(String name, long durationMs, String... tags) {
        Timer.builder(name).tags(tags).register(registry).record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** Times a supplier and returns its result. */
    public <T> T timed(String name, Supplier<T> block, String... tags) {
        return Timer.builder(name).tags(tags).register(registry).record(() -> block.get());
    }

    /** Times a Runnable. */
    public void timed(String name, Runnable block, String... tags) {
        Timer.builder(name).tags(tags).register(registry).record(block);
    }
}