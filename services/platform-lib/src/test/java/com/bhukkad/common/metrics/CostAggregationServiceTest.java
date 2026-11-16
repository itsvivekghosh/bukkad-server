package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies {@link CostAggregationService}: aggregation of per-request samples
 * into the {@code cost_usd{tenant,service,endpoint}} counters, zero-traffic
 * safety, and tenant tag propagation into separate series.
 */
class CostAggregationServiceTest {

    private static final long BYTES_PER_MB = 1_048_576L;
    private static final long BYTES_PER_GB = 1_073_741_824L;
    private static final Offset<Double> EPS = Offset.offset(1e-9);

    /** Fixed pricing so expected cost is deterministic. */
    private static CostProperties fixedPricing() {
        CostProperties props = new CostProperties();
        props.setCostPerCpuMsUsd(0.001);   // 1 USD per 1000 CPU-ms
        props.setCostPerMemMbUsd(0.01);    // 1 USD per 100 MB
        props.setCostPerNetGbUsd(0.09);    // 9 cents per GB
        return props;
    }

    private static Tags tags(String tenant, String service, String endpoint) {
        return Tags.of("tenant", tenant, "service", service, "endpoint", endpoint);
    }

    private static long count(SimpleMeterRegistry registry, String name) {
        return registry.getMeters().stream()
                .filter(m -> name.equals(m.getId().getName()))
                .mapToInt(m -> 1)
                .sum();
    }

    @Test
    void aggregatesSamplesIntoTaggedCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CostAggregationService service = new CostAggregationService(fixedPricing(), registry);

        // Sample 1: 2000 ms CPU, 10 MB alloc, 1 GB net.
        service.record(new CostSample("acme", "order", "/api/v1/orders",
                2_000_000_000L, 10 * BYTES_PER_MB, BYTES_PER_GB));
        // Sample 2: 1000 ms CPU, 5 MB alloc, 0 net.
        service.record(new CostSample("acme", "order", "/api/v1/orders",
                1_000_000_000L, 5 * BYTES_PER_MB, 0L));

        service.aggregate();

        Tags t = tags("acme", "order", "/api/v1/orders");
        // cpu: (2000 + 1000) ms; mem: (10 + 5) MB; net: 1 GB
        // cost: 3000*0.001 + 15*0.01 + 1*0.09 = 3.0 + 0.15 + 0.09 = 3.24
        assertThat(registry.counter("cost_usd", t).count()).isCloseTo(3.24, EPS);
        assertThat(registry.counter("cost_cpu_ms", t).count()).isCloseTo(3000.0, EPS);
        assertThat(registry.counter("cost_mem_mb", t).count()).isCloseTo(15.0, EPS);
        assertThat(registry.counter("cost_net_bytes", t).count()).isCloseTo((double) BYTES_PER_GB, EPS);
    }

    @Test
    void zeroTrafficDoesNotBreakAndEmitsNoSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CostAggregationService service = new CostAggregationService(fixedPricing(), registry);

        // No samples buffered: the tumbling window must be a safe no-op.
        assertThatCode(service::aggregate).doesNotThrowAnyException();

        assertThat(count(registry, "cost_usd")).isZero();
        assertThat(count(registry, "cost_cpu_ms")).isZero();
        assertThat(count(registry, "cost_mem_mb")).isZero();
        assertThat(count(registry, "cost_net_bytes")).isZero();
    }

    @Test
    void tenantTagPropagatesToSeparateSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CostAggregationService service = new CostAggregationService(fixedPricing(), registry);

        // Same service/endpoint, different tenants -> two independent series.
        service.record(new CostSample("acme", "order", "/api/v1/orders",
                2_000_000_000L, 10 * BYTES_PER_MB, BYTES_PER_GB));
        service.record(new CostSample("globex", "order", "/api/v1/orders",
                2_000_000_000L, 10 * BYTES_PER_MB, BYTES_PER_GB));

        service.aggregate();

        double expected = 2000 * 0.001 + 10 * 0.01 + 1 * 0.09; // 2.19
        assertThat(registry.counter("cost_usd", tags("acme", "order", "/api/v1/orders")).count())
                .isCloseTo(expected, EPS);
        assertThat(registry.counter("cost_usd", tags("globex", "order", "/api/v1/orders")).count())
                .isCloseTo(expected, EPS);

        // Exactly one series per tenant for cost_usd.
        assertThat(count(registry, "cost_usd")).isEqualTo(2);
    }

    @Test
    void endpointTagSeparatesSeriesForSameTenant() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CostAggregationService service = new CostAggregationService(fixedPricing(), registry);

        service.record(new CostSample("acme", "order", "/api/v1/orders",
                1_000_000_000L, 0, 0));
        service.record(new CostSample("acme", "order", "/api/v1/orders/{id}",
                2_000_000_000L, 0, 0));

        service.aggregate();

        assertThat(registry.counter("cost_usd", tags("acme", "order", "/api/v1/orders")).count())
                .isCloseTo(1.0, EPS);
        assertThat(registry.counter("cost_usd", tags("acme", "order", "/api/v1/orders/{id}")).count())
                .isCloseTo(2.0, EPS);
        assertThat(count(registry, "cost_usd")).isEqualTo(2);
    }
}