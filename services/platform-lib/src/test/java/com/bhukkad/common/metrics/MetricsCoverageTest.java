package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SLO settings and the business metrics façade (funnel counters + generic
 * tagged counter/timer/timed API with tag-pair validation).
 */
class MetricsCoverageTest {

    @Test
    void sloProperties_defaultsAndSetters() {
        SloProperties slo = new SloProperties();
        assertThat(slo.isEnabled()).isFalse();
        assertThat(slo.getLatencyTargetMs()).isEqualTo(500);
        assertThat(slo.getAvailabilityTargetPercent()).isEqualTo(99.5);
        assertThat(slo.getBurnRateWarning()).isEqualTo(1.0);
        assertThat(slo.getBurnRateCritical()).isEqualTo(2.0);
        assertThat(slo.getWindowMinutes()).isEqualTo(60);

        slo.setEnabled(true);
        slo.setLatencyTargetMs(300);
        slo.setAvailabilityTargetPercent(99.9);
        slo.setBurnRateWarning(1.5);
        slo.setBurnRateCritical(3.0);
        slo.setWindowMinutes(120);
        assertThat(slo.isEnabled()).isTrue();
        assertThat(slo.getLatencyTargetMs()).isEqualTo(300);
        assertThat(slo.getAvailabilityTargetPercent()).isEqualTo(99.9);
        assertThat(slo.getBurnRateWarning()).isEqualTo(1.5);
        assertThat(slo.getBurnRateCritical()).isEqualTo(3.0);
        assertThat(slo.getWindowMinutes()).isEqualTo(120);
    }

    @Test
    void funnel_countersAdvanceOneAtATime() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.search();
        metrics.menuView();
        metrics.cartAdd();
        metrics.checkout();
        metrics.payment();
        metrics.delivered();

        assertThat(registry.get("bhukkad.funnel.search").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("bhukkad.funnel.menu_view").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("bhukkad.funnel.cart_add").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("bhukkad.funnel.checkout").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("bhukkad.funnel.payment").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("bhukkad.funnel.delivered").counter().count()).isEqualTo(1.0);
    }

    @Test
    void genericCounterTimerAndTimed_registerLazilyWithTagPairs() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.increment("orders.created", "status", "settled");
        metrics.increment("orders.created", "status", "settled");
        metrics.increment("orders.cancelled");
        assertThat(registry.get("orders.created").tag("status", "settled").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("orders.cancelled").counter().count()).isEqualTo(1.0);

        metrics.record("handler.duration", 250, "endpoint", "search");
        assertThat(registry.get("handler.duration").tag("endpoint", "search").timer().count()).isEqualTo(1);

        String result = metrics.timed("work.duration", () -> "value", "job", "demo");
        assertThat(result).isEqualTo("value");
        assertThat(registry.get("work.duration").tag("job", "demo").timer().totalTime(
                java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void oddTagCountsAreRejectedEverywhere() {
        BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
        assertThatThrownBy(() -> metrics.increment("x", "lonely"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternating key/value pairs");
        assertThatThrownBy(() -> metrics.record("x", 1, "lonely"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.timed("x", () -> null, "lonely"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.increment("x", (String[]) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 argument(s)");
    }
}
