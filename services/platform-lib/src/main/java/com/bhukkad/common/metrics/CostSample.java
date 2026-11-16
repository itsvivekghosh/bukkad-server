package com.bhukkad.common.metrics;

/**
 * A single request's raw cost measurements, produced by {@link CostMetricsFilter}
 * (Servlet) and {@code ReactiveCostMetricsFilter} (WebFlux) and consumed by
 * {@link CostAggregationService}.
 *
 * <p>All byte/nanosecond values are deltas measured across the request; the
 * aggregation service applies the configured pricing constants to derive USD.
 */
public record CostSample(
        String tenant,
        String service,
        String endpoint,
        long cpuNs,
        long allocBytes,
        long netBytes
) {
}
