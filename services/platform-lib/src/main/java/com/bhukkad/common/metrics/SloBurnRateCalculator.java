package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Calculates the current error-budget burn rate from the per-endpoint SLI
 * meters maintained by {@link EndpointSloMetrics}.
 *
 * <p>A burn rate of {@code 1} means the observed bad-event ratio consumes the
 * error budget at the SLO's configured rate. A value above {@code 1} consumes
 * the budget faster than the SLO permits. The remaining-budget gauge is the
 * 30-day budget projected from the current burn rate over the configured
 * evaluation window and is always clamped to {@code [0, 1]}.</p>
 */
@Component
public class SloBurnRateCalculator {

    public static final String ERROR_BUDGET_REMAINING_METRIC = "slo_error_budget_remaining";
    public static final String BURN_RATE_METRIC = "slo_burn_rate";

    static final String REQUEST_TIMER = "bhukkad.http.requests";
    static final String ERROR_COUNTER = "bhukkad.http.errors";

    private static final int SLO_PERIOD_DAYS = 30;
    private static final int MINUTES_PER_DAY = 24 * 60;

    private final MeterRegistry registry;
    private final SloProperties sloProperties;
    private final AtomicBoolean gaugesRegistered = new AtomicBoolean();
    private final Map<SloType, AtomicReference<Double>> burnRates = new EnumMap<>(SloType.class);
    private final Map<SloType, AtomicReference<Double>> remainingBudgets = new EnumMap<>(SloType.class);

    public SloBurnRateCalculator(MeterRegistry registry, SloProperties sloProperties) {
        this.registry = registry;
        this.sloProperties = sloProperties;
        for (SloType sloType : SloType.values()) {
            burnRates.put(sloType, new AtomicReference<>(0.0));
            remainingBudgets.put(sloType, new AtomicReference<>(1.0));
        }
    }

    /**
     * Refreshes both SLO measurements. Gauge registration is deferred until
     * this method is first called, so applications that do not emit SLO
     * metrics do not publish misleading zero-valued gauges.
     */
    @Scheduled(
            fixedDelayString = "${app.slo.metrics-refresh-ms:60000}",
            initialDelayString = "${app.slo.metrics-initial-delay-ms:60000}")
    public void refresh() {
        if (!sloProperties.isEnabled()) {
            return;
        }
        calculate();
    }

    /**
     * Calculates the current latency and availability burn rates and updates
     * the exported gauges.
     */
    public SloSnapshot calculate() {
        double requestCount = requestCount();
        double errorCount = errorCount();
        double latencyGoodCount = requestsWithinLatencyTarget();

        Measurement latency = measurement(
                requestCount,
                Math.max(0.0, requestCount - latencyGoodCount),
                sloProperties.getLatencyTargetPercent());
        Measurement availability = measurement(
                requestCount,
                errorCount,
                sloProperties.getAvailabilityTargetPercent());

        burnRates.get(SloType.LATENCY).set(latency.burnRate());
        burnRates.get(SloType.AVAILABILITY).set(availability.burnRate());
        remainingBudgets.get(SloType.LATENCY).set(latency.remainingBudget());
        remainingBudgets.get(SloType.AVAILABILITY).set(availability.remainingBudget());

        registerGauges();
        return new SloSnapshot(
                latency.burnRate(),
                latency.remainingBudget(),
                availability.burnRate(),
                availability.remainingBudget());
    }

    private Measurement measurement(double requestCount, double badEventCount, double targetPercent) {
        double errorBudgetRatio = 1.0 - (targetPercent / 100.0);
        if (requestCount <= 0.0 || errorBudgetRatio <= 0.0) {
            return new Measurement(0.0, 1.0);
        }

        double badEventRatio = clamp(badEventCount / requestCount, 0.0, 1.0);
        double burnRate = badEventRatio / errorBudgetRatio;
        int windowMinutes = Math.max(1, sloProperties.getWindowMinutes());
        double windowFraction = (double) windowMinutes / (SLO_PERIOD_DAYS * MINUTES_PER_DAY);
        double remainingBudget = clamp(1.0 - (burnRate * windowFraction), 0.0, 1.0);
        return new Measurement(burnRate, remainingBudget);
    }

    private double requestCount() {
        return registry.find(REQUEST_TIMER).timers().stream()
                .mapToDouble(Timer::count)
                .sum();
    }

    private double errorCount() {
        return registry.find(ERROR_COUNTER).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double requestsWithinLatencyTarget() {
        long targetNanos = Duration.ofMillis(Math.max(1, sloProperties.getLatencyTargetMs())).toNanos();
        return registry.find(REQUEST_TIMER).timers().stream()
                .mapToDouble(timer -> countAtOrBelow(timer, targetNanos))
                .sum();
    }

    private double countAtOrBelow(Timer timer, long targetNanos) {
        CountAtBucket[] buckets = timer.takeSnapshot().histogramCounts();
        double closestBoundary = -1.0;
        double closestCount = 0.0;

        for (CountAtBucket bucket : buckets) {
            double boundary = bucket.bucket(TimeUnit.NANOSECONDS);
            if (boundary == targetNanos) {
                return bucket.count();
            }
            if (boundary <= targetNanos && boundary > closestBoundary) {
                closestBoundary = boundary;
                closestCount = bucket.count();
            }
        }
        return closestCount;
    }

    private void registerGauges() {
        if (!gaugesRegistered.compareAndSet(false, true)) {
            return;
        }

        try {
            for (SloType sloType : SloType.values()) {
                Gauge.builder(
                                BURN_RATE_METRIC,
                                burnRates.get(sloType),
                                reference -> reference.get())
                        .description("Current SLO error-budget burn-rate multiplier")
                        .tag("slo", sloType.metricValue())
                        .register(registry);
                Gauge.builder(
                                ERROR_BUDGET_REMAINING_METRIC,
                                remainingBudgets.get(sloType),
                                reference -> reference.get())
                        .description("Projected SLO error budget remaining over the 30-day SLO period")
                        .tag("slo", sloType.metricValue())
                        .register(registry);
            }
        } catch (RuntimeException exception) {
            gaugesRegistered.set(false);
            throw exception;
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record SloSnapshot(
            double latencyBurnRate,
            double latencyErrorBudgetRemaining,
            double availabilityBurnRate,
            double availabilityErrorBudgetRemaining) {
    }

    private record Measurement(double burnRate, double remainingBudget) {
    }

    private enum SloType {
        LATENCY("latency"),
        AVAILABILITY("availability");

        private final String metricValue;

        SloType(String metricValue) {
            this.metricValue = metricValue;
        }

        String metricValue() {
            return metricValue;
        }
    }
}
