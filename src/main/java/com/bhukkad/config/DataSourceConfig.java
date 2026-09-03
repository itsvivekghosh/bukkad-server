package com.bhukkad.config;

import com.bhukkad.common.datasource.ReadReplicaProperties;
import com.bhukkad.common.datasource.ReadReplicaRoutingDataSource;
import com.bhukkad.common.datasource.ReadReplicaSelector;
import com.bhukkad.common.datasource.ReadReplicaType;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the primary (write) datasource plus one or more read-replica pools.
 *
 * <p>When {@code app.datasource.read-replica.replicas} is configured, a pool is
 * created per replica entry and read connections are round-robined across them
 * (horizontal read scaling with N replicas). The legacy single
 * {@code app.datasource.read-replica.url} config keeps working unchanged. When
 * no replica is configured, the replica bean falls back to the write datasource
 * so the app runs on a single database without any config change.</p>
 */
@Configuration
@EnableConfigurationProperties(ReadReplicaProperties.class)
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("writeDataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource writeDataSource(@Qualifier("dataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Replica datasource exposed to health checks and anything else that needs
     * a direct handle. Falls back to the write datasource when no replica is
     * configured (reference-identical so callers can detect the fallback).
     * For multiple replicas, returns the first replica pool.
     */
    @Bean("readDataSource")
    public DataSource readDataSource(ReadReplicaProperties replicaProperties,
                                     @Qualifier("writeDataSource") DataSource writeDataSource,
                                     @Qualifier("dataSourceProperties") DataSourceProperties primaryProperties) {
        if (!replicaProperties.isConfigured()) {
            return writeDataSource;
        }
        if (replicaProperties.hasMultipleReplicas()) {
            return buildReplicaPool(replicaProperties.getReplicas().get(0), 0, replicaProperties, primaryProperties);
        }
        return buildSingleReplica(replicaProperties, primaryProperties);
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("writeDataSource") DataSource writeDataSource,
                                 @Qualifier("readDataSource") DataSource readDataSource,
                                 ReadReplicaProperties replicaProperties,
                                 @Qualifier("dataSourceProperties") DataSourceProperties primaryProperties) {
        ReadReplicaRoutingDataSource routingDataSource;
        Map<Object, Object> targets = new HashMap<>();
        targets.put(ReadReplicaType.PRIMARY, writeDataSource);

        if (replicaProperties.isConfigured()) {
            if (replicaProperties.hasMultipleReplicas()) {
                List<String> keys = new ArrayList<>();
                List<ReadReplicaProperties.Replica> replicas = replicaProperties.getReplicas();
                for (int i = 0; i < replicas.size(); i++) {
                    String key = replicaKey(i);
                    targets.put(key, buildReplicaPool(replicas.get(i), i, replicaProperties, primaryProperties));
                    keys.add(key);
                }
                routingDataSource = new ReadReplicaRoutingDataSource(new ReadReplicaSelector(keys));
            } else {
                targets.put(ReadReplicaType.REPLICA, readDataSource);
                routingDataSource = new ReadReplicaRoutingDataSource();
            }
        } else {
            // No replica configured: replica target = write datasource (single-DB fallback).
            targets.put(ReadReplicaType.REPLICA, writeDataSource);
            routingDataSource = new ReadReplicaRoutingDataSource();
        }

        routingDataSource.setTargetDataSources(targets);
        routingDataSource.setDefaultTargetDataSource(writeDataSource);
        routingDataSource.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }

    private DataSource buildSingleReplica(ReadReplicaProperties replicaProperties,
                                          DataSourceProperties primaryProperties) {
        ReadReplicaProperties.Hikari hikari = replicaProperties.getHikari();
        HikariDataSource replica = new HikariDataSource();
        replica.setPoolName(hikari.getPoolName());
        replica.setJdbcUrl(replicaProperties.getUrl());
        replica.setUsername(resolveUsername(replicaProperties.getUsername(), primaryProperties));
        replica.setPassword(resolvePassword(replicaProperties.getPassword(), primaryProperties));
        replica.setDriverClassName(primaryProperties.getDriverClassName());
        applyHikari(replica, hikari);
        return replica;
    }

    private DataSource buildReplicaPool(ReadReplicaProperties.Replica replica, int index,
                                        ReadReplicaProperties replicaProperties,
                                        DataSourceProperties primaryProperties) {
        ReadReplicaProperties.Hikari hikari = replica.getHikari() != null
                ? replica.getHikari()
                : replicaProperties.getHikari();
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName(hikari.getPoolName() + "-" + index);
        pool.setJdbcUrl(replica.getUrl());
        pool.setUsername(resolveUsername(replica.getUsername(), primaryProperties));
        pool.setPassword(resolvePassword(replica.getPassword(), primaryProperties));
        pool.setDriverClassName(primaryProperties.getDriverClassName());
        applyHikari(pool, hikari);
        return pool;
    }

    private void applyHikari(HikariDataSource dataSource, ReadReplicaProperties.Hikari hikari) {
        dataSource.setMaximumPoolSize(hikari.getMaximumPoolSize());
        dataSource.setMinimumIdle(hikari.getMinimumIdle());
        dataSource.setConnectionTimeout(hikari.getConnectionTimeout());
        dataSource.setIdleTimeout(hikari.getIdleTimeout());
        dataSource.setMaxLifetime(hikari.getMaxLifetime());
        dataSource.setReadOnly(hikari.isReadOnly());
    }

    private String resolveUsername(String replicaUsername, DataSourceProperties primaryProperties) {
        return replicaUsername != null ? replicaUsername : primaryProperties.getUsername();
    }

    private String resolvePassword(String replicaPassword, DataSourceProperties primaryProperties) {
        return replicaPassword != null ? replicaPassword : primaryProperties.getPassword();
    }

    private String replicaKey(int index) {
        return "REPLICA_" + index;
    }
}
