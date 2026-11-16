package com.bhukkad.common.web;

import com.bhukkad.common.metrics.CostAggregationService;
import com.bhukkad.common.metrics.CostProperties;
import com.bhukkad.common.metrics.CostSample;
import com.bhukkad.common.metrics.EndpointSloMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.management.MemoryUsage;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that attributes the cost of each HTTP request to a
 * {@code (tenant, service, endpoint)} and enqueues a {@link CostSample} for the
 * {@link CostAggregationService} tumbling window.
 *
 * <p>Measured per request:
 * <ul>
 *   <li>{@code cpu_ns} — {@code ThreadMXBean.getCurrentThreadCpuTime} delta.</li>
 *   <li>{@code alloc_bytes} — per-thread
 *     {@code getCurrentThreadAllocatedBytes} delta (Java 17+) or the heap +
 *     non-heap {@code MemoryMXBean} used delta as a fallback.</li>
 *   <li>{@code net_bytes} — request + response content length (best effort).</li>
 * </ul>
 *
 * <p>Tags are taken from MDC ({@code tenant}, {@code service}) and the request
 * URI (normalized to collapse numeric path segments), so cardinality stays
 * bounded. All metric names are namespaced under {@code cost_usd{...}}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnClass({HttpServletRequest.class, OncePerRequestFilter.class})
@ConditionalOnProperty(name = "app.cost.enabled", matchIfMissing = true)
public class CostMetricsFilter extends OncePerRequestFilter {

    private static final String TENANT_MDC_KEY = "tenant";
    private static final String SERVICE_MDC_KEY = "service";

    private final CostAggregationService aggregation;
    private final CostProperties properties;
    private final String serviceName;

    public CostMetricsFilter(CostAggregationService aggregation, CostProperties properties,
                             @Value("${spring.application.name:unknown}") String serviceName) {
        this.aggregation = aggregation;
        this.properties = properties;
        this.serviceName = serviceName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return false; // attribute every request so cost totals are complete
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Capture MDC at entry so we are unaffected by filters that clear MDC
        // (e.g. ProductionRequestLoggingFilter) in their finally blocks.
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        String tenant = resolve(mdcValue(mdc, TENANT_MDC_KEY), properties.getUnknownTenant());
        String service = resolve(mdcValue(mdc, SERVICE_MDC_KEY), serviceName);
        String endpoint = EndpointSloMetrics.normalizeUri(request.getRequestURI());

        long startCpuNs = CostMeasurement.cpuTime();
        long startAllocBytes = CostMeasurement.allocatedBytes();
        MemoryUsage startHeap = CostMeasurement.heapUsed();
        MemoryUsage startNonHeap = CostMeasurement.nonHeapUsed();
        long requestBytes = CostMeasurement.contentLength(request.getContentLength());

        try {
            filterChain.doFilter(request, response);
        } finally {
            long endCpuNs = CostMeasurement.cpuTime();
            long endAllocBytes = CostMeasurement.allocatedBytes();
            MemoryUsage endHeap = CostMeasurement.heapUsed();
            MemoryUsage endNonHeap = CostMeasurement.nonHeapUsed();
            // HttpServletResponse exposes no content-length getter; read the
            // header best-effort (chunked responses report 0).
            long responseBytes = CostMeasurement.contentLength(response.getHeader("Content-Length"));

            long cpuNs = CostMeasurement.cpuDelta(startCpuNs, endCpuNs);
            long allocBytes = CostMeasurement.allocBytes(startAllocBytes, endAllocBytes,
                    startHeap, endHeap, startNonHeap, endNonHeap);
            long netBytes = requestBytes + responseBytes;

            aggregation.record(CostMeasurement.sample(tenant, service, endpoint, cpuNs, allocBytes, netBytes));
        }
    }

    private static String resolve(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String mdcValue(Map<String, String> mdc, String key) {
        return mdc == null ? null : mdc.get(key);
    }
}