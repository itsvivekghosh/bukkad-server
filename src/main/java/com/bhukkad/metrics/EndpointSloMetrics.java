package com.bhukkad.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

    public EndpointSloMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String method, String uri, long durationMs, int status) {
        String normalizedUri = normalizeUri(uri);
        Timer timer = timers.computeIfAbsent(method + "|" + normalizedUri, k ->
                Timer.builder("bhukkad.http.requests")
                        .description("HTTP request latency")
                        .tags("method", method, "uri", normalizedUri)
                        .publishPercentileHistogram()
                        .sla(Duration.ofMillis(100), Duration.ofMillis(250),
                                Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofMillis(3000))
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

    /**
     * Collapses dynamic path segments (numeric ids) to a stable placeholder
     * so metrics cardinality stays bounded.
     */
    static String normalizeUri(String uri) {
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
