package com.bhukkad.common.web;

import com.bhukkad.common.metrics.CostAggregationService;
import com.bhukkad.common.metrics.CostProperties;
import com.bhukkad.common.metrics.CostSample;
import com.bhukkad.common.metrics.EndpointSloMetrics;
import jakarta.annotation.Nonnull;
import java.lang.management.MemoryUsage;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of {@link CostMetricsFilter} for WebFlux services
 * (architecture-audit.md §3.4). Attributes the cost of each exchange to a
 * {@code (tenant, service, endpoint)} and enqueues a {@link CostSample} for the
 * {@link CostAggregationService} tumbling window.
 *
 * <p>MDC is captured at filter entry (before the reactive chain) because MDC is
 * thread-local and is not guaranteed to propagate across Reactor thread hops;
 * the measured deltas are recorded in {@code doFinally} once the exchange
 * completes. All metric names are namespaced under {@code cost_usd{...}}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnClass(ServerWebExchange.class)
@ConditionalOnProperty(name = "app.cost.enabled", matchIfMissing = true)
public class ReactiveCostMetricsFilter implements WebFilter {

    private static final String TENANT_MDC_KEY = "tenant";
    private static final String SERVICE_MDC_KEY = "service";

    private final CostAggregationService aggregation;
    private final CostProperties properties;
    private final String serviceName;

    public ReactiveCostMetricsFilter(CostAggregationService aggregation, CostProperties properties,
                                     @Value("${spring.application.name:unknown}") String serviceName) {
        this.aggregation = aggregation;
        this.properties = properties;
        this.serviceName = serviceName;
    }

    @Override
    @Nonnull
    public Mono<Void> filter(@Nonnull ServerWebExchange exchange, @Nonnull WebFilterChain chain) {
        // Capture MDC at entry: it may not be available on the completion thread.
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        String tenant = resolve(mdcValue(mdc, TENANT_MDC_KEY), properties.getUnknownTenant());
        String service = resolve(mdcValue(mdc, SERVICE_MDC_KEY), serviceName);
        String endpoint = EndpointSloMetrics.normalizeUri(exchange.getRequest().getURI().getPath());

        long startCpuNs = CostMeasurement.cpuTime();
        long startAllocBytes = CostMeasurement.allocatedBytes();
        MemoryUsage startHeap = CostMeasurement.heapUsed();
        MemoryUsage startNonHeap = CostMeasurement.nonHeapUsed();
        long requestBytes = CostMeasurement.contentLength(
                exchange.getRequest().getHeaders().getContentLength());

        return Mono.defer(() -> chain.filter(exchange))
                .doFinally(signal -> {
                    long endCpuNs = CostMeasurement.cpuTime();
                    long endAllocBytes = CostMeasurement.allocatedBytes();
                    MemoryUsage endHeap = CostMeasurement.heapUsed();
                    MemoryUsage endNonHeap = CostMeasurement.nonHeapUsed();
                    long responseBytes = CostMeasurement.contentLength(
                            exchange.getResponse().getHeaders().getContentLength());

                    long cpuNs = CostMeasurement.cpuDelta(startCpuNs, endCpuNs);
                    long allocBytes = CostMeasurement.allocBytes(startAllocBytes, endAllocBytes,
                            startHeap, endHeap, startNonHeap, endNonHeap);
                    long netBytes = requestBytes + responseBytes;

                    aggregation.record(CostMeasurement.sample(tenant, service, endpoint, cpuNs, allocBytes, netBytes));
                });
    }

    private static String resolve(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String mdcValue(Map<String, String> mdc, String key) {
        return mdc == null ? null : mdc.get(key);
    }
}