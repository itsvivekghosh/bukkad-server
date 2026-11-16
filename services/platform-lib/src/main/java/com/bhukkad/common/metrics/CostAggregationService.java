package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tumbling-window cost aggregator (architecture-audit.md §3.4).
 *
 * <p>Drains per-request {@link CostSample}s recorded by the cost filters every
 * {@code app.cost.aggregation-window-seconds} (default 5 min) and emits them as
 * cumulative Prometheus counters namespaced under {@code cost_usd}:
 * {@code cost_usd}, {@code cost_cpu_ms}, {@code cost_mem_mb}, {@code cost_net_bytes},
 * each tagged with {@code tenant}/{@code service}/{@code endpoint}.
 *
 * <p>Counters are cumulative: each window's delta is added on top of the previous
 * value, so Prometheus {@code rate(cost_usd[5m])} yields the current spend rate
 * and {@code increase(cost_usd[1d])} yields per-tenant per-day spend for
 * capacity planning and anomaly detection.
 *
 * <p>The {@code @Scheduled} aggregate only runs in services that enable Spring
 * task scheduling ({@code @EnableScheduling}); elsewhere the bean is inert.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.cost.enabled", matchIfMissing = true)
public class CostAggregationService {

    private static final long NS_PER_MS = 1_000_000L;
    private static final long BYTES_PER_MB = 1_048_576L;
    private static final long BYTES_PER_GB = 1_073_741_824L;

    private static final String METRIC_COST_USD = "cost_usd";
    private static final String METRIC_COST_CPU_MS = "cost_cpu_ms";
    private static final String METRIC_COST_MEM_MB = "cost_mem_mb";
    private static final String METRIC_COST_NET_BYTES = "cost_net_bytes";

    private static final class WindowAggregate {
        long cpuNs;
        long allocBytes;
        long netBytes;
    }

    /** Tag identity for one aggregated series. */
    private record TagKey(String tenant, String service, String endpoint) {
    }

    private final CostProperties properties;
    private final MeterRegistry registry;
    private final ConcurrentLinkedQueue<CostSample> buffer = new ConcurrentLinkedQueue<>();

    public CostAggregationService(CostProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * Called by the cost filters (and by tests) to enqueue a per-request sample.
     * Thread-safe.
     */
    public void record(CostSample sample) {
        if (sample == null) {
            return;
        }
        buffer.offer(sample);
    }

    /**
     * One tumbling window: drain every sample buffered since the last window,
     * aggregate per {@code (tenant, service, endpoint)}, apply the configured
     * pricing constants and increment the cumulative counters.
     *
     * <p>Zero traffic is a no-op: an empty buffer performs no work and never
     * divides by zero.
     */
    @Scheduled(fixedDelayString = "#{${app.cost.aggregation-window-seconds:300} * 1000}")
    public void aggregate() {
        List<CostSample> batch = new ArrayList<>();
        CostSample drained;
        while ((drained = buffer.poll()) != null) {
            batch.add(drained);
        }
        if (batch.isEmpty()) {
            return;
        }

        Map<TagKey, WindowAggregate> byTags = new HashMap<>();
        for (CostSample sample : batch) {
            TagKey key = new TagKey(
                    sample.tenant() == null ? "" : sample.tenant(),
                    sample.service() == null ? "" : sample.service(),
                    sample.endpoint() == null ? "" : sample.endpoint());
            WindowAggregate agg = byTags.computeIfAbsent(key, k -> new WindowAggregate());
            agg.cpuNs += sample.cpuNs();
            agg.allocBytes += sample.allocBytes();
            agg.netBytes += sample.netBytes();
        }

        double windowCostUsd = 0.0;
        for (Map.Entry<TagKey, WindowAggregate> entry : byTags.entrySet()) {
            TagKey key = entry.getKey();
            WindowAggregate agg = entry.getValue();

            double cpuMs = agg.cpuNs / (double) NS_PER_MS;
            double memMb = agg.allocBytes / (double) BYTES_PER_MB;
            double netGb = agg.netBytes / (double) BYTES_PER_GB;

            double costUsd = cpuMs * properties.getCostPerCpuMsUsd()
                    + memMb * properties.getCostPerMemMbUsd()
                    + netGb * properties.getCostPerNetGbUsd();

            Tags tags = Tags.of("tenant", key.tenant(), "service", key.service(), "endpoint", key.endpoint());
            registry.counter(METRIC_COST_USD, tags).increment(costUsd);
            registry.counter(METRIC_COST_CPU_MS, tags).increment(cpuMs);
            registry.counter(METRIC_COST_MEM_MB, tags).increment(memMb);
            registry.counter(METRIC_COST_NET_BYTES, tags).increment(agg.netBytes);

            windowCostUsd += costUsd;
        }

        if (log.isInfoEnabled() && windowCostUsd > 0) {
            log.info("COST_AGGREGATED | windowSamples={} costUsd={}", batch.size(), windowCostUsd);
        }
    }
}
