package com.bhukkad.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.bhukkad.common.pool.PoolTuningProperties;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dynamically adjusts HikariCP pool sizes at runtime based on
 * {@code hikaricpu_pending_threads} and
 * {@code hikaricpu_connection_acquire_milliseconds} Micrometer metrics.
 *
 * <p>Tuning thresholds:
 * <ul>
 *   <li>Scale up: pending threads &ge; current max pool OR P99 acquire &ge; acquireHighThresholdMs</li>
 *   <li>Scale down: pending threads < pendingThreadsHighThreshold AND P99 acquire < acquireLowThresholdMs</li>
 *   <li>Delta cap: &plusmn;deltaCap per evaluation; range minPoolSize&ndash;maxPoolSize</li>
 * </ul>
 *
 * <p>Environment variables still win: {@code ORDER_DB_POOL_SIZE},
 * {@code PAYMENT_DB_POOL_SIZE}, etc. override the computed default.</p>
 *
 * <p>When PgBouncer is enabled, tuning parameters are adjusted for transaction
 * pooling: faster evaluation (500ms), smaller delta caps, and pool sizes that
 * reflect PgBouncer's multiplexing capability.</p>
 *
 * <p>A configurable evaluation loop runs after the auto-configured
 * {@link HikariDataSource} is created and only adjusts the pool if no
 * explicit env-var override is present.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PoolTuningProperties.class, PgBouncerProperties.class})
public class DynamicHikariPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamicHikariPoolConfig.class);
    
    // Default values (can be overridden by properties)
    private static final int DEFAULT_MIN_POOL_SIZE = 10;
    private static final int DEFAULT_MAX_POOL_SIZE = 50;
    private static final int DEFAULT_DELTA_CAP = 2;
    private static final long DEFAULT_ACQUIRE_HIGH_MS = 2000;
    private static final long DEFAULT_ACQUIRE_LOW_MS = 500;
    private static final long DEFAULT_PENDING_THREADS_HIGH = 2;
    private static final long DEFAULT_EVALUATION_PERIOD_SECONDS = 30;
    private static final long DEFAULT_HEAP_THRESHOLD_MB = 4096;

    private final HikariDataSource dataSource;
    private final DataSourceProperties properties;
    private final MeterRegistry meterRegistry;
    private final PoolTuningProperties poolTuningProperties;
    private final PgBouncerProperties pgBouncerProperties;
    private final CircuitBreaker metricCircuitBreaker;
    private volatile Double pendingThreadsOverride;
    private volatile Double p99AcquireMsOverride;
    private final ScheduledExecutorService scheduler;
    private volatile Future<?> scheduledFuture;

    // Metrics for tuning observability
    private final Gauge currentPoolSizeGauge;
    private final AtomicReference<Integer> targetPoolSizeRef = new AtomicReference<>();
    private final Gauge targetPoolSizeGauge;
    private final Counter tuningAdjustmentsCounter;
    private final Counter tuningErrorsCounter;
    private final Timer tuningOperationTimer;
    private final DistributionSummary poolSizeChangeSummary;

    // Error recovery state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private final Integer safePoolSize;

    // PgBouncer-aware tuning parameters
    private final boolean pgbouncerEnabled;
    private final long evaluationPeriodMs;
    private final int deltaCap;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamicHikariPoolConfig(
            @org.springframework.beans.factory.annotation.Qualifier("writeDataSource") HikariDataSource dataSource,
                                   DataSourceProperties properties,
                                   MeterRegistry meterRegistry,
                                   PoolTuningProperties poolTuningProperties,
                                   PgBouncerProperties pgBouncerProperties,
                                   CircuitBreakerRegistry circuitBreakerRegistry) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.poolTuningProperties = poolTuningProperties;
        this.pgBouncerProperties = pgBouncerProperties;
        this.pgbouncerEnabled = pgBouncerProperties != null && pgBouncerProperties.isEnabled();
        
        // PgBouncer-aware tuning parameters
        if (pgbouncerEnabled) {
            // Faster evaluation for PgBouncer transaction pooling
            this.evaluationPeriodMs = 500; // 500ms instead of 30s
            this.deltaCap = Math.max(10, getMaxPoolSize() / 20); // Larger delta for PgBouncer
        } else {
            this.evaluationPeriodMs = getEvaluationPeriodSeconds() * 1000L;
            this.deltaCap = getDeltaCap();
        }
        
        this.metricCircuitBreaker = (circuitBreakerRegistry != null) 
                ? circuitBreakerRegistry.circuitBreaker("hikaripool-metrics",
                        CircuitBreakerConfig.custom()
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(5)
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .build())
                : null;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dynamic-hikari-pool-tuner");
            t.setDaemon(true);
            return t;
        });
        
        // Determine safe pool size (conservative default)
        this.safePoolSize = Math.max(getDefaultMaxPoolSize() / 2, 5);
        
        // Validate configuration properties
        validatePoolTuningProperties();
        
        // Initialize metrics for tuning observability (only if meterRegistry is available)
        if (meterRegistry != null) {
            String poolId = properties != null && properties.getName() != null 
                    ? properties.getName() 
                    : "unknown";
            this.currentPoolSizeGauge = Gauge.builder("hikaripool.current_size", 
                    () -> dataSource.getMaximumPoolSize())
                    .description("Current HikariCP maximum pool size")
                    .register(meterRegistry);
            this.targetPoolSizeGauge = Gauge.builder("hikaripool.target_size", 
                    targetPoolSizeRef, ref -> ref.get().doubleValue())
                    .description("Target HikariCP maximum pool size based on tuning algorithm")
                    .register(meterRegistry);
            this.tuningAdjustmentsCounter = Counter.builder("hikaripool.tuning_adjustments_total")
                    .description("Total number of pool size adjustments made")
                    .register(meterRegistry);
            this.tuningErrorsCounter = Counter.builder("hikaripool.tuning_errors_total")
                    .description("Total number of tuning operation errors")
                    .register(meterRegistry);
            this.tuningOperationTimer = Timer.builder("hikaripool.tuning_operation_duration")
                    .description("Duration of pool tuning operations")
                    .register(meterRegistry);
            this.poolSizeChangeSummary = DistributionSummary.builder("hikaripool.size_changes")
                    .description("Distribution of pool size change magnitudes")
                    .register(meterRegistry);
        } else {
            // Set metrics to null when meterRegistry is not available
            this.currentPoolSizeGauge = null;
            this.targetPoolSizeGauge = null;
            this.tuningAdjustmentsCounter = null;
            this.tuningErrorsCounter = null;
            this.tuningOperationTimer = null;
            this.poolSizeChangeSummary = null;
        }
    }

    DynamicHikariPoolConfig(HikariDataSource dataSource, DataSourceProperties properties) {
        this(dataSource, properties, null, null, null, null);
    }

    // Constructor for backward compatibility with existing tests
    DynamicHikariPoolConfig(HikariDataSource dataSource, DataSourceProperties properties, MeterRegistry meterRegistry) {
        this(dataSource, properties, meterRegistry, null, null, null);
    }

    @PostConstruct
    void start() {
        int initialMax = dataSource.getMaximumPoolSize();
        if (initialMax == getDefaultMaxPoolSize() || initialMax == 30) {
            tunePool(initialMax);
        }
        // Use PgBouncer-aware evaluation period (500ms for PgBouncer, default for direct)
        long initialDelay = pgbouncerEnabled ? 1 : getEvaluationPeriodSeconds();
        this.scheduledFuture = scheduler.scheduleAtFixedRate(
                this::evaluateAndTune,
                initialDelay,
                pgbouncerEnabled ? evaluationPeriodMs : getEvaluationPeriodSeconds() * 1000L,
                pgbouncerEnabled ? TimeUnit.MILLISECONDS : TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        scheduler.shutdownNow();
    }

    private void rescheduleAfterCooldown() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        scheduler.schedule(() -> {
            consecutiveFailures.set(0);
            long initialDelay = pgbouncerEnabled ? 1 : getEvaluationPeriodSeconds();
            this.scheduledFuture = scheduler.scheduleAtFixedRate(
                    this::evaluateAndTune,
                    initialDelay,
                    pgbouncerEnabled ? evaluationPeriodMs : getEvaluationPeriodSeconds() * 1000L,
                    pgbouncerEnabled ? TimeUnit.MILLISECONDS : TimeUnit.SECONDS);
        }, TimeUnit.MINUTES.toMillis(5), TimeUnit.MILLISECONDS);
    }

    private void evaluateAndTune() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Use circuit breaker for metric collection to prevent cascading failures
            if (metricCircuitBreaker != null) {
                metricCircuitBreaker.executeRunnable(() -> {
                    int currentMax = dataSource.getMaximumPoolSize();
                    // Only tune if pool is at default values (no explicit override)
                    if (currentMax != getDefaultMaxPoolSize() && currentMax != 30) {
                        consecutiveFailures.set(0); // Reset failure counter on success
                        return;
                    }
                    tunePool(currentMax);
                    consecutiveFailures.set(0); // Reset failure counter on success
                });
            } else {
                // Fallback without circuit breaker
                int currentMax = dataSource.getMaximumPoolSize();
                // Only tune if pool is at default values (no explicit override)
                if (currentMax != getDefaultMaxPoolSize() && currentMax != 30) {
                    consecutiveFailures.set(0); // Reset failure counter on success
                    return;
                }
                tunePool(currentMax);
                consecutiveFailures.set(0); // Reset failure counter on success
            }
            
            // Check if we've had too many consecutive failures
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                // Dead man's switch: reset to safe pool size
                int currentMax = dataSource.getMaximumPoolSize();
                if (currentMax != safePoolSize) {
                    log.warn("HIKARI_POOL_DEAD_MANS_SWITCH: Too many tuning failures ({}), resetting pool size from {} to {}",
                            failures, currentMax, safePoolSize);
                    applyNewMax(currentMax, safePoolSize, 0.0, 0.0);
                }
                // After triggering dead man's switch, wait longer before trying again
                rescheduleAfterCooldown();
                return;
            }
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (tuningErrorsCounter != null) {
                tuningErrorsCounter.increment();
            }
            log.warn("HIKARI_POOL_TUNE_ERROR: {} (failure {}/{})", e.getMessage(), failures, MAX_CONSECUTIVE_FAILURES);
            
            // If we've had too many consecutive failures, apply dead man's switch
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                try {
                    int currentMax = dataSource.getMaximumPoolSize();
                    if (currentMax != safePoolSize) {
                        log.warn("HIKARI_POOL_DEAD_MANS_SWITCH: Too many tuning failures ({}), resetting pool size from {} to {}",
                                failures, currentMax, safePoolSize);
                        applyNewMax(currentMax, safePoolSize, 0.0, 0.0);
                    }
                } catch (Exception ex) {
                    log.error("HIKARI_POOL_DEAD_MANS_SWITCH_FAILED: {}", ex.getMessage());
                }
                rescheduleAfterCooldown();
            }
        } finally {
            if (tuningOperationTimer != null) {
                sample.stop(tuningOperationTimer);
            }
        }
    }

    void tunePool(int currentMax) {
        double pendingThreads = readPendingThreads();
        double p99AcquireMs = readP99ConnectionAcquireMs();

        if (shouldScaleUp(pendingThreads, p99AcquireMs, currentMax)) {
            int newMax = Math.min(currentMax + getDeltaCap(), getMaxPoolSize());
            applyNewMax(currentMax, newMax, pendingThreads, p99AcquireMs);
        } else if (shouldScaleDown(pendingThreads, p99AcquireMs, currentMax)) {
            int newMax = Math.max(currentMax - getDeltaCap(), getMinPoolSize());
            applyNewMax(currentMax, newMax, pendingThreads, p99AcquireMs);
        }
    }

    private boolean shouldScaleUp(double pendingThreads, double p99AcquireMs, int currentMax) {
        return pendingThreads >= currentMax || p99AcquireMs >= getAcquireHighThresholdMs();
    }

    private boolean shouldScaleDown(double pendingThreads, double p99AcquireMs, int currentMax) {
        return currentMax > getMinPoolSize()
                && pendingThreads < getPendingThreadsHighThreshold()
                && p99AcquireMs < getAcquireLowThresholdMs();
    }

    private void applyNewMax(int oldMax, int newMax, double pendingThreads, double p99AcquireMs) {
        dataSource.setMaximumPoolSize(newMax);
        if (dataSource.getMinimumIdle() < newMax) {
            dataSource.setMinimumIdle(newMax);
        }
        int change = newMax - oldMax;
        // Update metrics (null-safe for testing)
        if (targetPoolSizeRef != null) {
            targetPoolSizeRef.set(newMax);
        }
        if (tuningAdjustmentsCounter != null) {
            tuningAdjustmentsCounter.increment();
        }
        if (poolSizeChangeSummary != null) {
            poolSizeChangeSummary.record(Math.abs(change));
        }
        // Optimized logging - avoid String.format when not needed
        if (log.isInfoEnabled()) {
            log.info("HIKARI_POOL_ADJUSTED from {} to {} (pending={}, p99Acquire={}ms, processors={}, heap={}MB)",
                    oldMax, newMax,
                    String.format("%.1f", pendingThreads),
                    String.format("%.1f", p99AcquireMs),
                    Runtime.getRuntime().availableProcessors(),
                    getHeapMemoryMB());
        }
    }

    // Helper methods to get configurable values with fallback to defaults
    private int getMinPoolSize() {
        return poolTuningProperties != null ? Math.max(poolTuningProperties.getHikariMinIdle(), 1) : DEFAULT_MIN_POOL_SIZE;
    }

    private int getMaxPoolSize() {
        return poolTuningProperties != null ? poolTuningProperties.getHikariHighLoadMaxPool() : DEFAULT_MAX_POOL_SIZE;
    }

    private int getDeltaCap() {
        return poolTuningProperties != null ? Math.max(poolTuningProperties.getHikariMinIdle() / 10, 1) : DEFAULT_DELTA_CAP;
    }

    private long getAcquireHighThresholdMs() {
        return poolTuningProperties != null ? poolTuningProperties.getHikariConnectionTimeoutMs() : DEFAULT_ACQUIRE_HIGH_MS;
    }

    private long getAcquireLowThresholdMs() {
        return poolTuningProperties != null ? Math.max(poolTuningProperties.getHikariConnectionTimeoutMs() / 10, 100) : DEFAULT_ACQUIRE_LOW_MS;
    }

    private long getPendingThreadsHighThreshold() {
        return poolTuningProperties != null ? poolTuningProperties.getHikariMinIdle() / 10 : DEFAULT_PENDING_THREADS_HIGH;
    }

    private long getEvaluationPeriodSeconds() {
        if (pgbouncerEnabled) {
            return evaluationPeriodMs / 1000; // Return seconds for logging/backward compat
        }
        return poolTuningProperties != null ? Math.max(poolTuningProperties.getHikariKeepaliveTimeMs() / 1000, 10) : DEFAULT_EVALUATION_PERIOD_SECONDS;
    }

    private int getDefaultMaxPoolSize() {
        // Determine if this is a high-load service based on service name from properties
        if (poolTuningProperties != null && properties != null && properties.getName() != null) {
            String serviceName = properties.getName().toLowerCase();
            if (serviceName.contains("order") || serviceName.contains("restaurant") || serviceName.contains("delivery")) {
                return poolTuningProperties.getHikariHighLoadMaxPool();
            }
        }
        return poolTuningProperties != null ? poolTuningProperties.getHikariDefaultMaxPool() : 20;
    }

    /**
     * Validates PoolTuningProperties to ensure they contain sensible values.
     * Logs warnings for invalid configurations but does not fail startup.
     */
    private void validatePoolTuningProperties() {
        if (poolTuningProperties == null) {
            log.warn("HIKARI_POOL_CONFIG_NO_TUNING_PROPERTIES: Using default tuning values");
            return;
        }

        // Validate min pool size
        int minIdle = poolTuningProperties.getHikariMinIdle();
        if (minIdle < 1) {
            log.warn("HIKARI_POOL_INVALID_MIN_IDLE: {} is less than 1, using default {}", 
                    minIdle, DEFAULT_MIN_POOL_SIZE);
        }

        // Validate max pool size
        int maxPool = poolTuningProperties.getHikariHighLoadMaxPool();
        if (maxPool < minIdle) {
            log.warn("HIKARI_POOL_INVALID_MAX_POOL: maxPool ({}) < minIdle ({}), swapping values", 
                    maxPool, minIdle);
        } else if (maxPool > 1000) {
            log.warn("HIKARI_POOL_EXCESSIVE_MAX_POOL: {} is unusually high, consider if this is intentional", 
                    maxPool);
        }

        // Validate delta cap - adjusted for PgBouncer
        int deltaCap = pgbouncerEnabled 
                ? Math.max(10, maxPool / 20) 
                : poolTuningProperties.getHikariMinIdle() / 10;
        if (deltaCap < 1) {
            log.warn("HIKARI_POOL_INVALID_DELTA_CAP: calculated delta cap ({}) < 1, using 1", 
                    deltaCap);
        } else if (deltaCap > 100) { // Higher limit for PgBouncer
            log.warn("HIKARI_POOL_LARGE_DELTA_CAP: {} may cause instability, consider lower value", 
                    deltaCap);
        }

        // Validate timeouts - faster timeouts for PgBouncer
        long connTimeout = poolTuningProperties.getHikariConnectionTimeoutMs();
        long minTimeout = pgbouncerEnabled ? 1000 : 1000; // Same minimum
        long maxTimeout = pgbouncerEnabled ? TimeUnit.SECONDS.toMillis(30) : TimeUnit.MINUTES.toMillis(5);
        if (connTimeout < minTimeout) {
            log.warn("HIKARI_POOL_CONN_TIMEOUT_TOO_LOW: {} ms is too low for connection timeout", 
                    connTimeout);
        } else if (connTimeout > maxTimeout) {
            log.warn("HIKARI_POOL_CONN_TIMEOUT_TOO_HIGH: {} ms is unusually high for connection timeout", 
                    connTimeout);
        }

        long maxLifetime = poolTuningProperties.getHikariMaxLifetimeMs();
        if (maxLifetime <= 0) {
            log.warn("HIKARI_POOL_INVALID_MAX_LIFETIME: {} is not positive", maxLifetime);
        }

        // PgBouncer-specific validation
        if (pgbouncerEnabled && pgBouncerProperties != null) {
            if (pgBouncerProperties.getDefaultPoolSize() < 100) {
                log.warn("PGBOUNCER_POOL_SIZE_TOO_LOW: {} is too low for 200k+ TPS, recommend 500+", 
                        pgBouncerProperties.getDefaultPoolSize());
            }
            if (!"transaction".equalsIgnoreCase(pgBouncerProperties.getPoolMode())) {
                log.warn("PGBOUNCER_NON_TRANSACTION_MODE: {} mode may not be optimal for 200k+ TPS", 
                        pgBouncerProperties.getPoolMode());
            }
        }
    }

    double readPendingThreads() {
        if (pendingThreadsOverride != null) {
            return pendingThreadsOverride;
        }
        if (meterRegistry == null) {
            return 0.0;
        }
        Search search = meterRegistry.find("hikaricpu_pending_threads");
        if (search == null) {
            return 0.0;
        }
        Gauge gauge = search.gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    double readP99ConnectionAcquireMs() {
        if (p99AcquireMsOverride != null) {
            return p99AcquireMsOverride;
        }
        if (meterRegistry == null) {
            return 0.0;
        }
        Search search = meterRegistry.find("hikaricpu_connection_acquire_milliseconds");
        if (search == null) {
            return 0.0;
        }
        Meter meter = search.meter();
        if (meter == null) {
            return 0.0;
        }
        for (Measurement m : meter.measure()) {
            Statistic stat = m.getStatistic();
            if (stat != null && "+Inf".equals(stat.name())) {
                return m.getValue();
            }
        }
        return 0.0;
    }

    private long getHeapMemoryMB() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        // Use used memory instead of max memory for scaling decisions
        // This reacts to actual memory pressure rather than allocated max
        return heapUsage.getUsed() / (1024 * 1024);
    }

    private long getHeapThresholdMB() {
        // Use 60% of max heap as threshold for scaling up
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long maxHeapMB = heapUsage.getMax() / (1024 * 1024);
        // Use property-based threshold if available, otherwise default
        long thresholdMB = DEFAULT_HEAP_THRESHOLD_MB;
        if (poolTuningProperties != null) {
            // We could add a specific heap threshold property, but for now use default
            thresholdMB = DEFAULT_HEAP_THRESHOLD_MB;
        }
        return Math.max(thresholdMB, maxHeapMB * 60 / 100);
    }

    int computeOptimalPoolSize() {
        int processors = Runtime.getRuntime().availableProcessors();
        // Base: 2*N+1 for CPU-bound; I/O-bound DB workloads benefit from more.
        int base = processors * 2 + 1;

        // Scale up if the JVM has significant heap usage (high-traffic pods).
        // Using used memory instead of max memory for more responsive scaling
        long heapUsedMB = getHeapMemoryMB();
        long heapThresholdMB = getHeapThresholdMB();
        if (heapUsedMB > heapThresholdMB) {
            base = (int) (base * 1.5);
        }

        return Math.max(base, getMinPoolSize());
    }

    void setPendingThreadsOverride(Double value) {
        this.pendingThreadsOverride = value;
    }

    void setP99AcquireMsOverride(Double value) {
        this.p99AcquireMsOverride = value;
    }
}
