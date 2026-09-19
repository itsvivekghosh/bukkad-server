package com.bhukkad.common.datasource;

import com.bhukkad.common.AbstractPostgresIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime validation for {@link DynamicHikariPoolConfig}: verifies the tuner
 * raises the pool when connection pressure spikes and lowers it when idle.
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamicHikariPoolTunerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("bhukkad_pool_test")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    @Test
    void tuner_scalesUpUnderConnectionPressure() throws Exception {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .driverClassName(POSTGRES.getDriverClassName())
                .build();
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(5000);

        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());

        DynamicHikariPoolConfig tuner = new DynamicHikariPoolConfig(ds, properties);
        tuner.setPendingThreadsOverride(15.0); // simulate 15 pending threads
        tuner.setP99AcquireMsOverride(3000.0); // simulate 3s acquire time
        tuner.tunePool(10);

        assertThat(ds.getMaximumPoolSize()).isGreaterThan(10);
    }

    @Test
    void tuner_scalesDownWhenIdle() throws Exception {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .driverClassName(POSTGRES.getDriverClassName())
                .build();
        ds.setMaximumPoolSize(30);
        ds.setMinimumIdle(30);

        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());

        DynamicHikariPoolConfig tuner = new DynamicHikariPoolConfig(ds, properties);
        tuner.setPendingThreadsOverride(0.5); // low pending threads
        tuner.setP99AcquireMsOverride(200.0); // fast acquire
        tuner.tunePool(30);

        assertThat(ds.getMaximumPoolSize()).isLessThan(30);
    }

    @Test
    void tuner_respectsHardCeiling() throws Exception {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .driverClassName(POSTGRES.getDriverClassName())
                .build();
        ds.setMaximumPoolSize(48);
        ds.setMinimumIdle(48);

        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());

        DynamicHikariPoolConfig tuner = new DynamicHikariPoolConfig(ds, properties);
        tuner.setPendingThreadsOverride(100.0);
        tuner.setP99AcquireMsOverride(5000.0);
        tuner.tunePool(48);

        assertThat(ds.getMaximumPoolSize()).isEqualTo(50); // capped at MAX_POOL_SIZE=50
    }
}
