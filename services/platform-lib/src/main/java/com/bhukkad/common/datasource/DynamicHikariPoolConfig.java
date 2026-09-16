package com.bhukkad.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Configuration;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Dynamically adjusts HikariCP pool sizes based on runtime CPU and memory
 * for very heavy traffic scenarios.
 *
 * <p>Formula:
 * <ul>
 *   <li>Base pool size = {@code availableProcessors() * 2 + 1} (CPU-bound baseline)</li>
 *   <li>If heap > 4GB: add 50% headroom</li>
 *   <li>Cap at 50 connections per service instance (tune per deployment)</li>
 * </ul>
 *
 * <p>Environment variables still win: {@code ORDER_DB_POOL_SIZE},
 * {@code PAYMENT_DB_POOL_SIZE}, etc. override the computed default.</p>
 *
 * <p>This runs after the auto-configured {@link HikariDataSource} is created
 * and only adjusts the pool if no explicit env-var override is present.</p>
 */
@Configuration
public class DynamicHikariPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamicHikariPoolConfig.class);
    private static final int MAX_POOL_SIZE = 50;
    private static final long HEAP_THRESHOLD_MB = 4096;

    private final HikariDataSource dataSource;
    private final DataSourceProperties properties;

    public DynamicHikariPoolConfig(HikariDataSource dataSource, DataSourceProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @PostConstruct
    public void adjustPoolSize() {
        // Only adjust if the pool size is at the generic default (20 or 30)
        // and no explicit env-var override was provided.
        int currentMax = dataSource.getMaximumPoolSize();
        if (currentMax != 20 && currentMax != 30) {
            log.info("HIKARI_POOL_KEEP_EXISTING maxPoolSize={}", currentMax);
            return;
        }

        int computed = computeOptimalPoolSize();
        int newMax = Math.min(computed, MAX_POOL_SIZE);

        if (newMax != currentMax) {
            dataSource.setMaximumPoolSize(newMax);
            // Also bump minimum-idle to match for warm pool behavior
            if (dataSource.getMinimumIdle() < newMax) {
                dataSource.setMinimumIdle(newMax);
            }
            log.info("HIKARI_POOL_ADJUSTED from {} to {} (processors={}, heap={}MB)",
                    currentMax, newMax,
                    Runtime.getRuntime().availableProcessors(),
                    getHeapMemoryMB());
        } else {
            log.info("HIKARI_POOL_UNCHANGED maxPoolSize={} (computed={})", currentMax, computed);
        }
    }

    private int computeOptimalPoolSize() {
        int processors = Runtime.getRuntime().availableProcessors();
        // Base: 2*N+1 for CPU-bound; I/O-bound DB workloads benefit from more.
        int base = processors * 2 + 1;

        // Scale up if the JVM has significant heap (high-traffic pods).
        long heapMB = getHeapMemoryMB();
        if (heapMB > HEAP_THRESHOLD_MB) {
            base = (int) (base * 1.5);
        }

        return Math.max(base, 10);
    }

    private long getHeapMemoryMB() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return heapUsage.getMax() / (1024 * 1024);
    }
}
