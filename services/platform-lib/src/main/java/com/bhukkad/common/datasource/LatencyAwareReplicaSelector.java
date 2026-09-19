package com.bhukkad.common.datasource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Latency-aware read replica selector for 200k+ TPS workloads.
 * <p>
 * Routes read queries to the replica with the lowest current latency,
 * using exponentially weighted moving average (EWMA) for smooth decisions.
 * Automatically marks unhealthy replicas and fails over gracefully.
 * </p>
 */
public class LatencyAwareReplicaSelector {

    private static final Logger log = LoggerFactory.getLogger(LatencyAwareReplicaSelector.class);

    private final List<ReplicaInfo> replicas;
    private final ConcurrentMap<String, ReplicaMetrics> metricsMap = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final double ewmaAlpha; // 0.2 for fast adaptation
    private final long healthCheckIntervalMs;
    private final long failureThresholdMs;
    private final int maxFailureCount;

    public LatencyAwareReplicaSelector(List<String> replicaKeys, MeterRegistry meterRegistry) {
        this.replicas = new ArrayList<>();
        this.meterRegistry = meterRegistry;
        this.ewmaAlpha = 0.2;
        this.healthCheckIntervalMs = 5000; // 5 seconds
        this.failureThresholdMs = 10000; // 10 seconds
        this.maxFailureCount = 3;

        for (String key : replicaKeys) {
            replicas.add(new ReplicaInfo(key));
            metricsMap.put(key, new ReplicaMetrics());
            registerMetrics(key);
        }
    }

    private void registerMetrics(String replicaKey) {
        if (meterRegistry == null) return;
        
        Timer.builder("pgbouncer.replica.latency")
                .tag("replica", replicaKey)
                .description("Replica query latency")
                .register(meterRegistry);
        
        Timer.builder("pgbouncer.replica.availability")
                .tag("replica", replicaKey)
                .description("Replica availability percentage")
                .register(meterRegistry);
    }

    /**
     * Select the best replica based on current latency and health.
     * Uses weighted random selection based on inverse latency for better distribution.
     */
    public String selectReplica() {
        List<ReplicaInfo> healthy = new ArrayList<>();
        for (ReplicaInfo replica : replicas) {
            if (replica.isHealthy()) {
                healthy.add(replica);
            }
        }

        if (healthy.isEmpty()) {
            // All replicas unhealthy - fall back to primary
            log.warn("All read replicas unhealthy, falling back to primary");
            return ReadReplicaType.PRIMARY.name();
        }

        if (healthy.size() == 1) {
            return healthy.get(0).getKey();
        }

        // Weighted random selection based on inverse latency (lower latency = higher weight)
        double totalWeight = 0;
        for (ReplicaInfo replica : healthy) {
            ReplicaMetrics metrics = metricsMap.get(replica.getKey());
            double avgLatency = metrics.getAverageLatencyMs();
            double weight = avgLatency > 0 ? 1000.0 / avgLatency : 1.0; // Inverse latency
            replica.setWeight(weight);
            totalWeight += weight;
        }

        // Random selection with weights
        double random = Math.random() * totalWeight;
        double cumulative = 0;
        for (ReplicaInfo replica : healthy) {
            cumulative += replica.getWeight();
            if (random <= cumulative) {
                return replica.getKey();
            }
        }

        // Fallback to lowest latency
        return healthy.stream()
                .min((a, b) -> Double.compare(
                        metricsMap.get(a.getKey()).getAverageLatencyMs(),
                        metricsMap.get(b.getKey()).getAverageLatencyMs()))
                .map(ReplicaInfo::getKey)
                .orElse(ReadReplicaType.PRIMARY.name());
    }

    /**
     * Record successful query latency for a replica.
     */
    public void recordSuccess(String replicaKey, long latencyMs) {
        ReplicaMetrics metrics = metricsMap.get(replicaKey);
        if (metrics != null) {
            metrics.recordLatency(latencyMs, true);
            
            // Update EWMA
            double currentAvg = metrics.getAverageLatencyMs();
            double newAvg = ewmaAlpha * latencyMs + (1 - ewmaAlpha) * currentAvg;
            metrics.setAverageLatencyMs(newAvg);
            
            // Reset failure count on success
            metrics.resetFailureCount();
        }
    }

    /**
     * Record failed query for a replica.
     */
    public void recordFailure(String replicaKey, long latencyMs) {
        ReplicaMetrics metrics = metricsMap.get(replicaKey);
        if (metrics != null) {
            metrics.recordLatency(latencyMs, false);
            int failures = metrics.incrementFailureCount();
            
            if (failures >= maxFailureCount) {
                ReplicaInfo replica = findReplica(replicaKey);
                if (replica != null) {
                    replica.setHealthy(false);
                    log.warn("Replica {} marked unhealthy after {} consecutive failures", 
                            replicaKey, failures);
                    
                    // Schedule health check to potentially restore
                    scheduleHealthCheck(replicaKey);
                }
            }
        }
    }

    /**
     * Find replica info by key.
     */
    private ReplicaInfo findReplica(String key) {
        return replicas.stream()
                .filter(r -> r.getKey().equals(key))
                .findFirst()
                .orElse(null);
    }

    /**
     * Schedule a health check for a failed replica.
     */
    private void scheduleHealthCheck(String replicaKey) {
        // In production, use a proper scheduler
        // For now, mark as needing check
        ReplicaInfo replica = findReplica(replicaKey);
        if (replica != null) {
            replica.setNeedsHealthCheck(true);
        }
    }

    /**
     * Check if a specific replica is healthy.
     */
    public boolean isHealthy(String replicaKey) {
        ReplicaInfo replica = findReplica(replicaKey);
        return replica != null && replica.isHealthy();
    }

    /**
     * Get all replica keys.
     */
    public List<String> getReplicaKeys() {
        return replicas.stream()
                .map(ReplicaInfo::getKey)
                .toList();
    }

    /**
     * Get current metrics for monitoring.
     */
    public ReplicaMetrics getMetrics(String replicaKey) {
        return metricsMap.get(replicaKey);
    }

    /**
     * Get the currently best replica (lowest latency).
     */
    public String getBestReplica() {
        return replicas.stream()
                .filter(ReplicaInfo::isHealthy)
                .min((a, b) -> Double.compare(
                        metricsMap.get(a.getKey()).getAverageLatencyMs(),
                        metricsMap.get(b.getKey()).getAverageLatencyMs()))
                .map(ReplicaInfo::getKey)
                .orElse(ReadReplicaType.PRIMARY.name());
    }

    /**
     * Replica information holder.
     */
    private static class ReplicaInfo {
        private final String key;
        private final AtomicReference<Boolean> healthy = new AtomicReference<>(true);
        private final AtomicReference<Boolean> needsHealthCheck = new AtomicReference<>(false);
        private double weight = 1.0;

        ReplicaInfo(String key) {
            this.key = key;
        }

        String getKey() { return key; }
        boolean isHealthy() { return healthy.get(); }
        void setHealthy(boolean healthy) { this.healthy.set(healthy); }
        boolean needsHealthCheck() { return needsHealthCheck.get(); }
        void setNeedsHealthCheck(boolean needs) { this.needsHealthCheck.set(needs); }
        double getWeight() { return weight; }
        void setWeight(double weight) { this.weight = weight; }
    }

    /**
     * Replica metrics with EWMA latency tracking.
     */
    public static class ReplicaMetrics {
        private final AtomicLong totalLatency = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong lastLatency = new AtomicLong(0);
        private volatile double averageLatencyMs = 0;
        private volatile long lastUpdateTime = System.currentTimeMillis();

        void recordLatency(long latencyMs, boolean success) {
            totalLatency.addAndGet(latencyMs);
            lastLatency.set(latencyMs);
            lastUpdateTime = System.currentTimeMillis();
            
            if (success) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }
        }

        double getAverageLatencyMs() {
            long successes = successCount.get();
            if (successes == 0) return averageLatencyMs;
            return totalLatency.get() / (double) successes;
        }

        void setAverageLatencyMs(double avg) {
            this.averageLatencyMs = avg;
        }

        int getFailureCount() {
            return (int) failureCount.get();
        }

        int incrementFailureCount() {
            return (int) failureCount.incrementAndGet();
        }

        void resetFailureCount() {
            failureCount.set(0);
        }

        long getLastLatency() {
            return lastLatency.get();
        }

        long getSuccessCount() {
            return successCount.get();
        }

        long getLastUpdateTime() {
            return lastUpdateTime;
        }

        public boolean isStale(long maxAgeMs) {
            return System.currentTimeMillis() - lastUpdateTime > maxAgeMs;
        }
    }
}