package com.bhukkad.common.web;

import com.bhukkad.common.metrics.CostSample;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;

/**
 * Stateless per-request measurement helpers shared by {@link CostMetricsFilter}
 * (Servlet) and {@link ReactiveCostMetricsFilter} (WebFlux).
 *
 * <p>CPU time is read from {@link ThreadMXBean#getCurrentThreadCpuTime()}.
 * Allocation is read from {@code com.sun.management.ThreadMXBean}
 * ({@code getCurrentThreadAllocatedBytes()}, per-thread allocation metering
 * available on Java 17+) when the JVM supports it, otherwise it falls back to
 * the process-wide heap + non-heap used delta from
 * {@link java.lang.management.MemoryMXBean}. All methods are static and hold no
 * state, so a single instance can be shared across concurrent requests.
 */
final class CostMeasurement {

    private CostMeasurement() {
    }

    static long cpuTime() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        return tmx.isCurrentThreadCpuTimeSupported() ? tmx.getCurrentThreadCpuTime() : -1L;
    }

    /**
     * Per-thread allocated bytes delta start point, or {@code -1} when
     * allocation metering is unsupported (caller falls back to heap/non-heap).
     */
    static long allocatedBytes() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        if (tmx instanceof com.sun.management.ThreadMXBean sun
                && sun.isThreadAllocatedMemorySupported()) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return -1L;
    }

    static MemoryUsage heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    }

    static MemoryUsage nonHeapUsed() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
    }

    /**
     * Resolves the allocation delta: per-thread allocated-bytes delta when both
     * samples are valid, otherwise the heap + non-heap used delta. Never
     * negative.
     */
    static long allocBytes(long startAlloc, long endAlloc, MemoryUsage startHeap,
                           MemoryUsage endHeap, MemoryUsage startNonHeap,
                           MemoryUsage endNonHeap) {
        if (startAlloc >= 0 && endAlloc >= 0) {
            return Math.max(0L, endAlloc - startAlloc);
        }
        long delta = (endHeap.getUsed() - startHeap.getUsed())
                + (endNonHeap.getUsed() - startNonHeap.getUsed());
        return Math.max(0L, delta);
    }

    static long cpuDelta(long startCpuNs, long endCpuNs) {
        if (startCpuNs < 0 || endCpuNs < 0) {
            return 0L;
        }
        return Math.max(0L, endCpuNs - startCpuNs);
    }

    static long contentLength(int length) {
        return length > 0 ? length : 0L;
    }

    static long contentLength(Long length) {
        return length != null && length > 0 ? length : 0L;
    }

    static long contentLength(String headerValue) {
        if (headerValue == null) {
            return 0L;
        }
        try {
            long value = Long.parseLong(headerValue.trim());
            return value > 0 ? value : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Builds an immutable sample from already-captured deltas. */
    static CostSample sample(String tenant, String service, String endpoint,
                             long cpuNs, long allocBytes, long netBytes) {
        return new CostSample(tenant, service, endpoint, cpuNs, allocBytes, netBytes);
    }
}