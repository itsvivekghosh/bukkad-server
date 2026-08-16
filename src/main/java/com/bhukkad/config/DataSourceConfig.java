package com.bhukkad.config;

import com.bhukkad.datasource.ReadReplicaProperties;
import com.bhukkad.datasource.ReadReplicaRoutingDataSource;
import com.bhukkad.datasource.ReadReplicaType;
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
import java.util.HashMap;
import java.util.Map;

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

    @Bean("readDataSource")
    public DataSource readDataSource(ReadReplicaProperties replicaProperties,
                                     @Qualifier("writeDataSource") DataSource writeDataSource,
                                     @Qualifier("dataSourceProperties") DataSourceProperties primaryProperties) {
        if (!replicaProperties.isConfigured()) {
            return writeDataSource;
        }

        HikariDataSource replica = new HikariDataSource();
        replica.setPoolName(replicaProperties.getHikari().getPoolName());
        replica.setJdbcUrl(replicaProperties.getUrl());
        replica.setUsername(resolveUsername(replicaProperties, primaryProperties));
        replica.setPassword(resolvePassword(replicaProperties, primaryProperties));
        replica.setDriverClassName(primaryProperties.getDriverClassName());
        replica.setMaximumPoolSize(replicaProperties.getHikari().getMaximumPoolSize());
        replica.setMinimumIdle(replicaProperties.getHikari().getMinimumIdle());
        replica.setConnectionTimeout(replicaProperties.getHikari().getConnectionTimeout());
        replica.setIdleTimeout(replicaProperties.getHikari().getIdleTimeout());
        replica.setMaxLifetime(replicaProperties.getHikari().getMaxLifetime());
        replica.setReadOnly(replicaProperties.getHikari().isReadOnly());
        return replica;
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("writeDataSource") DataSource writeDataSource,
                                 @Qualifier("readDataSource") DataSource readDataSource) {
        ReadReplicaRoutingDataSource routingDataSource = new ReadReplicaRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(ReadReplicaType.PRIMARY, writeDataSource);
        targets.put(ReadReplicaType.REPLICA, readDataSource);
        routingDataSource.setTargetDataSources(targets);
        routingDataSource.setDefaultTargetDataSource(writeDataSource);
        routingDataSource.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }

    private String resolveUsername(ReadReplicaProperties replicaProperties, DataSourceProperties primaryProperties) {
        return replicaProperties.getUsername() != null
                ? replicaProperties.getUsername()
                : primaryProperties.getUsername();
    }

    private String resolvePassword(ReadReplicaProperties replicaProperties, DataSourceProperties primaryProperties) {
        return replicaProperties.getPassword() != null
                ? replicaProperties.getPassword()
                : primaryProperties.getPassword();
    }
}
