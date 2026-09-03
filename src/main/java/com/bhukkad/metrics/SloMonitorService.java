package com.bhukkad.metrics;

import com.bhukkad.common.logging.alert.AlertCategory;
import com.bhukkad.common.logging.alert.AlertSeverity;
import com.bhukkad.common.metrics.SloProperties;
import com.bhukkad.logging.alert.AlertService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per-replica SLO monitor: tracks a rolling request/error window and fires
 * latency and error-budget burn-rate alerts.
 *
 * <p>The window is intentionally per-instance so each replica evaluates its own
 * SLO (the deployment scales horizontally); alert <i>deduplication</i> across
 * replicas is handled by {@link AlertService}, which claims a Redis key before
 * firing. Meter names match the producers in {@link EndpointSloMetrics}:
 * {@code bhukkad.http.requests} (timer) and {@code bhukkad.http.errors}
 * (counter).</p>
 */
@Slf4j
@Service
public class SloMonitorService {

    private final MeterRegistry registry;
    private final AlertService alertService;
    private final SloProperties sloProperties;
    private final Deque<long[]> window = new ArrayDeque<>();

    public SloMonitorService(MeterRegistry registry, AlertService alertService, SloProperties sloProperties) {
        this.registry = registry;
        this.alertService = alertService;
        this.sloProperties = sloProperties;
    }

    @Scheduled(fixedDelayString = "${app.slo.interval-ms:60000}")
    public void monitor() {
        if (!sloProperties.isEnabled()) {
            return;
        }
        try {
            List<Timer> timers = new java.util.ArrayList<>(registry.find("bhukkad.http.requests").timers());
            long total = timers.stream().mapToLong(Timer::count).sum();
            long errors = registry.find("bhukkad.http.errors").counters().stream()
                    .mapToLong(counter -> (long) counter.count()).sum();
            double p95Ms = computeMaxP95Ms(timers);
            long now = System.currentTimeMillis();

            synchronized (window) {
                window.addLast(new long[]{now, total, errors});
                long windowMillis = sloProperties.getWindowMinutes() * 60_000L;
                while (window.size() > 1 && now - window.peekFirst()[0] > windowMillis) {
                    window.pollFirst();
                }
                long[] baseline = window.peekFirst();
                if (baseline == null || window.size() < 2) {
                    log.debug("SLO monitor skipping evaluation: insufficient window data");
                    return;
                }
                long windowTotal = total - baseline[1];
                long windowErrors = errors - baseline[2];
                if (windowTotal <= 0) {
                    log.debug("SLO monitor skipping evaluation: no requests in window");
                    return;
                }
                evaluate(p95Ms, windowTotal, windowErrors);
            }
        } catch (Exception e) {
            log.warn("SLO monitor error", e);
        }
    }

    private void evaluate(double p95Ms, long windowTotal, long windowErrors) {
        if (p95Ms > sloProperties.getLatencyTargetMs()) {
            log.warn("SLO latency breach | p95Ms={} targetMs={}", p95Ms, sloProperties.getLatencyTargetMs());
            alertService.alert(
                    AlertSeverity.WARNING, AlertCategory.SYSTEM,
                    "SLO latency target breached",
                    Map.of("p95Ms", p95Ms, "latencyTargetMs", sloProperties.getLatencyTargetMs(),
                            "windowTotal", windowTotal, "windowErrors", windowErrors));
        }

        double errorRate = (double) windowErrors / windowTotal;
        double allowedErrorRate = (100.0 - sloProperties.getAvailabilityTargetPercent()) / 100.0;
        double burnRate = allowedErrorRate <= 0 ? 0 : errorRate / allowedErrorRate;

        if (burnRate >= sloProperties.getBurnRateCritical()) {
            log.warn("SLO critical burn rate | burnRate={} errorRate={}", burnRate, errorRate);
            alertService.alertException("SloMonitorService",
                    "SLO error budget burn rate critical: " + String.format("%.2f", burnRate), null);
        } else if (burnRate >= sloProperties.getBurnRateWarning()) {
            log.warn("SLO elevated burn rate | burnRate={} errorRate={}", burnRate, errorRate);
            alertService.alert(
                    AlertSeverity.WARNING, AlertCategory.SYSTEM,
                    "SLO error budget burn rate elevated",
                    Map.of("burnRate", burnRate, "errorRate", errorRate,
                            "windowTotal", windowTotal, "windowErrors", windowErrors));
        }
    }

    /** @return the maximum p95 latency across all {@code bhukkad.http.requests} timers, in millis. */
    static double computeMaxP95Ms(Collection<Timer> timers) {
        double max = 0.0;
        for (Timer t : timers) {
            try {
                double p95 = 0.0;
                for (ValueAtPercentile vap : t.takeSnapshot().percentileValues()) {
                    if (vap.percentile() == 0.95) {
                        p95 = vap.value(TimeUnit.MILLISECONDS);
                        break;
                    }
                }
                if (p95 > max) max = p95;
            } catch (Exception e) {
                log.trace("Could not read P95 from timer", e);
            }
        }
        return max;
    }
}
