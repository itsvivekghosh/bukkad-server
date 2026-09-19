package com.bhukkad.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.Statistic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicHikariPoolConfigTest {

    private DynamicHikariPoolConfig lastConfig;

    @AfterEach
    void shutdownScheduler() {
        if (lastConfig != null) {
            try {
                lastConfig.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void postConstruct_noEnvOverride_sizesPoolFromCpuAndMemory() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(20);
        MeterRegistry registry = mockMetricsRegistry(0.0, 500.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        int tuned = ds.getMaximumPoolSize();
        assertThat(tuned).isGreaterThanOrEqualTo(10);
        assertThat(tuned).isLessThanOrEqualTo(50);
        assertThat(ds.getMinimumIdle()).isEqualTo(tuned);
    }

    @Test
    void postConstruct_envOverride_preservesExplicitPoolSize() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(40);
        ds.setMinimumIdle(40);
        MeterRegistry registry = mockMetricsRegistry(0.0, 500.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        assertThat(ds.getMaximumPoolSize()).isEqualTo(40);
    }

    @Test
    void scaleUp_pendingThreadsAtMaxPool_growsByDeltaCap() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(20);
        MeterRegistry registry = mockMetricsRegistry(25.0, 500.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        assertThat(ds.getMaximumPoolSize()).isGreaterThan(20);
    }

    @Test
    void scaleUp_p99AcquireAboveThreshold_growsByDeltaCap() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(20);
        MeterRegistry registry = mockMetricsRegistry(0.0, 3000.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        assertThat(ds.getMaximumPoolSize()).isGreaterThan(20);
    }

    @Test
    void scaleDown_healthyMetrics_shrinksByDeltaCap() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(20);
        MeterRegistry registry = mockMetricsRegistry(0.0, 100.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        assertThat(ds.getMaximumPoolSize()).isEqualTo(18);
    }

    @Test
    void noShrink_belowMinimumPool_holdsFloor() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(20);
        MeterRegistry registry = mockMetricsRegistry(0.0, 100.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        assertThat(ds.getMaximumPoolSize()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void noGrow_aboveMaximumPool_respectsCap() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(50);
        ds.setMinimumIdle(50);
        MeterRegistry registry = mockMetricsRegistry(100.0, 5000.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        assertThat(ds.getMaximumPoolSize()).isEqualTo(50);
    }

    @Test
    void stableMetrics_noChange_poolUnchanged() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(20);
        MeterRegistry registry = mockMetricsRegistry(1.0, 800.0);
        DynamicHikariPoolConfig config = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = config;
        config.start();
        assertThat(ds.getMaximumPoolSize()).isEqualTo(20);
    }

    @Test
    void missingMetrics_returnsZero_noThrownException() {
        MeterRegistry registry = mock(MeterRegistry.class);
        MeterRegistry.Config config = mock(MeterRegistry.Config.class);
        when(registry.config()).thenReturn(config);
        when(config.pauseDetector()).thenReturn(null);
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(20);
        DynamicHikariPoolConfig dynamicHikariPoolConfig = new DynamicHikariPoolConfig(ds, new DataSourceProperties(), registry);
        lastConfig = dynamicHikariPoolConfig;
        dynamicHikariPoolConfig.start();
        assertThat(ds.getMaximumPoolSize()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void computeOptimalPoolSize_returnsValueWithinBounds() {
        MeterRegistry registry = mock(MeterRegistry.class);
        MeterRegistry.Config config = mock(MeterRegistry.Config.class);
        when(registry.config()).thenReturn(config);
        when(config.pauseDetector()).thenReturn(null);
        HikariDataSource ds = mock(HikariDataSource.class);
        DynamicHikariPoolConfig dynamicHikariPoolConfig = new DynamicHikariPoolConfig(
                ds, new DataSourceProperties(), registry);
        lastConfig = dynamicHikariPoolConfig;
        int computed = dynamicHikariPoolConfig.computeOptimalPoolSize();
        assertThat(computed).isGreaterThanOrEqualTo(10);
        assertThat(computed).isLessThanOrEqualTo(50);
    }

    private static MeterRegistry mockMetricsRegistry(double pendingThreadsValue, double p99AcquireValue) {
        MeterRegistry registry = mock(MeterRegistry.class);
        MeterRegistry.Config config = mock(MeterRegistry.Config.class);
        when(registry.config()).thenReturn(config);
        when(config.pauseDetector()).thenReturn(null); // Return null for pause detector

        Search pendingSearch = mock(Search.class);
        Gauge pendingGauge = mock(Gauge.class);
        when(pendingGauge.value()).thenReturn(pendingThreadsValue);
        when(pendingSearch.gauge()).thenReturn(pendingGauge);
        when(registry.find("hikaricpu_pending_threads")).thenReturn(pendingSearch);

        Search acquireSearch = mock(Search.class);
        Meter acquireMeter = mock(Meter.class);
        Statistic infStat = mock(Statistic.class);
        when(infStat.name()).thenReturn("+Inf");
        Measurement infMeasurement = mock(Measurement.class);
        when(infMeasurement.getStatistic()).thenReturn(infStat);
        when(infMeasurement.getValue()).thenReturn(p99AcquireValue);
        when(acquireMeter.measure()).thenReturn(List.of(infMeasurement));
        when(acquireSearch.meter()).thenReturn(acquireMeter);
        when(registry.find("hikaricpu_connection_acquire_milliseconds")).thenReturn(acquireSearch);

        return registry;
    }
}
