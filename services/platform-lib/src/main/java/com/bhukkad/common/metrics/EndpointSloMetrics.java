package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-endpoint SLO/SLI metrics: latency (histogram), error count and
 * availability. Each metric is tagged with the HTTP method and normalized
 * URI (e.g. /api/v1/orders/customer/{id} instead of a raw numeric id) so a
 * dashboard can group by endpoint without unbounded cardinality.
 */
@Component
public class EndpointSloMetrics {

    private static final Set<Duration> DEFAULT_LATENCY_OBJECTIVES = Set.of(
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofMillis(1000),
            Duration.ofMillis(3000));

    private final MeterRegistry registry;
    private final SloProperties sloProperties;
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

    public EndpointSloMetrics(MeterRegistry registry) {
        this(registry, new SloProperties());
    }

    @Autowired
    public EndpointSloMetrics(MeterRegistry registry, SloProperties sloProperties) {
        this.registry = registry;
        this.sloProperties = sloProperties;
    }

    public void record(String method, String uri, long durationMs, int status) {
        String normalizedUri = normalizeUri(uri);
        Timer timer = timers.computeIfAbsent(method + "|" + normalizedUri, k ->
                Timer.builder("bhukkad.http.requests")
                        .description("HTTP request latency")
                        .tags("method", method, "uri", normalizedUri)
                        .publishPercentileHistogram()
                        .serviceLevelObjectives(latencyObjectives().toArray(new Duration[0]))
                        .register(registry));
        timer.record(Duration.ofMillis(durationMs));

        if (status >= 500) {
            Counter errorCounter = errorCounters.computeIfAbsent(method + "|" + normalizedUri, k ->
                    Counter.builder("bhukkad.http.errors")
                            .description("HTTP 5xx errors")
                            .tags("method", method, "uri", normalizedUri)
                            .register(registry));
            errorCounter.increment();
        }
    }

    private Set<Duration> latencyObjectives() {
        Set<Duration> objectives = new LinkedHashSet<>(DEFAULT_LATENCY_OBJECTIVES);
        long targetMs = Math.max(1, sloProperties.getLatencyTargetMs());
        objectives.add(Duration.ofMillis(targetMs));
        return objectives;
    }

    /**
     * Collapses dynamic path segments (numeric ids) to a stable placeholder
     * so metrics cardinality stays bounded. Shared with the cost-attribution
     * filters ({@code CostMetricsFilter}) so endpoint tags are normalized
     * consistently across SLO and cost metrics.
     */
    public static String normalizeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        String[] segments = uri.split("/");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.matches("\\d+")) {
                sb.append("/{id}");
            } else {
                sb.append('/').append(segment);
            }
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }
}
