package com.bhukkad.common.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.bhukkad.common.datasource.ReadReplicaProperties;
import com.bhukkad.common.datasource.ReadReplicaType;
import com.bhukkad.common.pool.PoolTuningProperties;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PgBouncer-aware datasource configuration for 200k+ TPS workloads.
 * <p>
 * When PgBouncer is enabled, this configuration provides optimized datasource
 * beans that route through PgBouncer (transaction pooling mode) instead of
 * connecting directly to PostgreSQL.
 * </p>
 *
 * <p>Key optimizations for 200k+ TPS:
 * <ul>
 *   <li>Prepared statement caching with extended query protocol</li>
 *   <li>Batched insert rewriting</li>
 *   <li>TCP keep-alive for connection reuse</li>
 *   <li>Reduced HikariCP pool sizes (connections go to PgBouncer, not PostgreSQL)</li>
 *   <li>Fast connection validation with lightweight queries</li>
 *   <li>Lazy connection proxy to avoid borrowing until needed</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({PgBouncerProperties.class, PoolTuningProperties.class})
public class PgBouncerDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(PgBouncerDataSourceConfig.class);

    @PostConstruct
    public void init() {
        log.info("PgBouncerDataSourceConfig initialized");
    }

    @PreDestroy
    public void cleanup() {
        log.info("PgBouncerDataSourceConfig shutting down");
    }

    /**
     * Provides PgBouncer-aware configuration for the write datasource.
     * This method provides configuration properties that can be used by
     * DataSourceConfig to configure PgBouncer-aware HikariCP pools.
     *
     * @param pgBouncerProperties PgBouncer configuration properties
     * @return PgBouncer write pool configuration
     */
    @Bean("pgBouncerWriteConfig")
    public HikariConfig pgBouncerWriteConfig(PgBouncerProperties pgBouncerProperties) {
        if (!pgBouncerProperties.isEnabled()) {
            return null;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pgBouncerProperties.buildJdbcUrl());
        config.setUsername(pgBouncerProperties.getUsername());
        config.setPassword(pgBouncerProperties.getPassword());
        config.setDriverClassName("org.postgresql.Driver");

        // Optimize for PgBouncer transaction pooling
        config.setPoolName("BhukkadPgBouncerWritePool");
        config.setMaximumPoolSize(getWritePoolSize());
        config.setMinimumIdle(getWriteMinIdle());
        config.setConnectionTimeout(10_000); // 10s - fast fail for PgBouncer
        config.setIdleTimeout(30_000); // 30s - connections return to PgBouncer quickly
        config.setMaxLifetime(120_000); // 2min - keep connections fresh
        config.setConnectionTestQuery("SELECT 1"); // Lightweight validation
        config.setValidationTimeout(2_000); // 2s validation timeout
        config.setLeakDetectionThreshold(5_000); // 5s leak detection

        // Prepared statement cache for 200k+ TPS
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "1000");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("preferQueryMode", "extended");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("ApplicationName", "bhukkad-write");
        config.addDataSourceProperty("socketTimeout", "30");
        config.addDataSourceProperty("loginTimeout", "5");
        config.addDataSourceProperty("connectTimeout", "5");

        log.info("Configured PgBouncer write datasource: pool={}, maxPoolSize={}, minIdle={}",
                config.getPoolName(), config.getMaximumPoolSize(), config.getMinimumIdle());
        return config;
    }

    /**
     * Provides PgBouncer-aware configuration for the read datasource.
     *
     * @param pgBouncerProperties PgBouncer configuration properties
     * @return PgBouncer read pool configuration
     */
    @Bean("pgBouncerReadConfig")
    public HikariConfig pgBouncerReadConfig(PgBouncerProperties pgBouncerProperties) {
        if (!pgBouncerProperties.isEnabled()) {
            return null;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pgBouncerProperties.buildJdbcUrl());
        config.setUsername(pgBouncerProperties.getUsername());
        config.setPassword(pgBouncerProperties.getPassword());
        config.setDriverClassName("org.postgresql.Driver");

        // Optimize for PgBouncer transaction pooling (read replicas)
        config.setPoolName("BhukkadPgBouncerReadPool");
        config.setMaximumPoolSize(getReadPoolSize());
        config.setMinimumIdle(getReadMinIdle());
        config.setConnectionTimeout(5_000); // 5s - faster for reads
        config.setIdleTimeout(20_000); // 20s
        config.setMaxLifetime(60_000); // 1min
        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(1_000); // 1s validation timeout

        // Prepared statement cache for read queries
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "500");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("preferQueryMode", "extended");
        config.addDataSourceProperty("readOnly", "true");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("ApplicationName", "bhukkad-read");
        config.addDataSourceProperty("socketTimeout", "15");
        config.addDataSourceProperty("loginTimeout", "5");
        config.addDataSourceProperty("connectTimeout", "5");

        log.info("Configured PgBouncer read datasource: pool={}, maxPoolSize={}, minIdle={}",
                config.getPoolName(), config.getMaximumPoolSize(), config.getMinimumIdle());
        return config;
    }

    /**
     * Creates PgBouncer-aware read replica pools for horizontal read scaling.
     * Each replica gets its own HikariCP pool connected through PgBouncer,
     * enabling 200k+ TPS across multiple read replicas.
     *
     * @param pgBouncerProperties PgBouncer configuration properties
     * @param replicaProperties   Read replica configuration properties
     * @return Map of replica key to HikariDataSource for PgBouncer replicas
     */
    @Bean("pgBouncerReplicaDataSources")
    public Map<String, HikariDataSource> pgBouncerReplicaDataSources(
            PgBouncerProperties pgBouncerProperties,
            ReadReplicaProperties replicaProperties) {

        Map<String, HikariDataSource> replicas = new HashMap<>();

        if (!pgBouncerProperties.isEnabled() || !replicaProperties.isConfigured()) {
            return replicas;
        }

        List<ReadReplicaProperties.Replica> replicaList = replicaProperties.getReplicas();
        if (replicaList == null || replicaList.isEmpty()) {
            // Single replica fallback
            HikariDataSource replica = buildPgBouncerReplicaPool(
                    replicaProperties.getUrl(),
                    replicaProperties.getUsername(),
                    replicaProperties.getPassword(),
                    pgBouncerProperties,
                    0);
            replicas.put(ReadReplicaType.REPLICA.name(), replica);
        } else {
            for (int i = 0; i < replicaList.size(); i++) {
                ReadReplicaProperties.Replica replica = replicaList.get(i);
                HikariDataSource pool = buildPgBouncerReplicaPool(
                        replica.getUrl(),
                        replica.getUsername(),
                        replica.getPassword(),
                        pgBouncerProperties,
                        i);
                replicas.put("REPLICA_" + i, pool);
            }
        }

        log.info("Configured {} PgBouncer read replica pools for 200k+ TPS", replicas.size());
        return replicas;
    }

    private HikariDataSource buildPgBouncerReplicaPool(String url, String username, String password,
                                                       PgBouncerProperties pgBouncerProperties, int index) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("BhukkadPgBouncerReplica-" + index);
        pool.setJdbcUrl(url);
        pool.setUsername(username != null ? username : pgBouncerProperties.getUsername());
        pool.setPassword(password != null ? password : pgBouncerProperties.getPassword());
        pool.setDriverClassName("org.postgresql.Driver");

        pool.setMaximumPoolSize(getReadPoolSize());
        pool.setMinimumIdle(getReadMinIdle());
        pool.setConnectionTimeout(5_000);
        pool.setIdleTimeout(20_000);
        pool.setMaxLifetime(60_000);
        pool.setConnectionTestQuery("SELECT 1");
        pool.setValidationTimeout(1_000);

        // Prepared statement caching for replica reads
        pool.addDataSourceProperty("cachePrepStmts", "true");
        pool.addDataSourceProperty("prepStmtCacheSize", "500");
        pool.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        pool.addDataSourceProperty("preferQueryMode", "extended");
        pool.addDataSourceProperty("readOnly", "true");
        pool.addDataSourceProperty("tcpKeepAlive", "true");
        pool.addDataSourceProperty("ApplicationName", "bhukkad-replica-" + index);
        pool.addDataSourceProperty("socketTimeout", "30");
        pool.addDataSourceProperty("loginTimeout", "5");
        pool.addDataSourceProperty("connectTimeout", "5");

        return pool;
    }

    /**
     * Provides PgBouncer metrics collector for monitoring.
     */
    @Bean
    public PgBouncerMetricsCollector pgBouncerMetricsCollector(
            @Qualifier("writeDataSource") DataSource writeDataSource,
            @Qualifier("readDataSource") DataSource readDataSource,
            @Qualifier("pgBouncerReplicaDataSources") Map<String, HikariDataSource> replicaDataSources,
            MeterRegistry meterRegistry,
            PgBouncerProperties pgBouncerProperties) {
        return new PgBouncerMetricsCollector(writeDataSource, readDataSource, replicaDataSources, meterRegistry, pgBouncerProperties);
    }

    private int getWritePoolSize() {
        // For PgBouncer, we need fewer connections since they're multiplexed
        // Typical: 50-100 connections to PgBouncer per service instance
        return Integer.getInteger("pgbouncer.write.pool.size", 80);
    }

    private int getWriteMinIdle() {
        return Integer.getInteger("pgbouncer.write.pool.minIdle", 20);
    }

    private int getReadPoolSize() {
        return Integer.getInteger("pgbouncer.read.pool.size", 40);
    }

    private int getReadMinIdle() {
        return Integer.getInteger("pgbouncer.read.pool.minIdle", 10);
    }
}