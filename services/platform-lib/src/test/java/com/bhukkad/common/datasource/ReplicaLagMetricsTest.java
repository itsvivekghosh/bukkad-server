package com.bhukkad.common.datasource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V-17: the {@code bhukkad.replica.lag} gauge (Prometheus
 * {@code bhukkad_replica_lag_seconds}) exists only when a replica is
 * configured, reads pg_stat_replication replay lag from the primary, and
 * degrades to NaN (metric absent for Prometheus) on probe failure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplicaLagMetricsTest {

    @Mock
    private DataSource dataSource;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static ReadReplicaProperties replicaConfigured() {
        ReadReplicaProperties properties = new ReadReplicaProperties();
        properties.setEnabled(true);
        properties.setUrl("jdbc:postgresql://replica:5432/db");
        return properties;
    }

    @Test
    void noReplicaConfigured_gaugeAbsent_andNoProbe() {
        ReadReplicaProperties properties = new ReadReplicaProperties(); // enabled=false

        ReplicaLagMetrics metrics = new ReplicaLagMetrics(dataSource, properties, registry);
        metrics.refresh();

        assertThat(registry.find(ReplicaLagMetrics.METRIC_NAME).gauge())
                .as("single-DB deployment: nothing to measure, nothing registered")
                .isNull();
        verifyNoInteractions(dataSource);
    }

    @Test
    void replicaConfigured_gaugePresent_reportsReplayLagSeconds() throws Exception {
        stubProbe(1.5, false);
        ReplicaLagMetrics metrics = new ReplicaLagMetrics(dataSource, replicaConfigured(), registry);

        metrics.refresh();

        assertThat(registry.get(ReplicaLagMetrics.METRIC_NAME).gauge().value()).isEqualTo(1.5);
    }

    @Test
    void noStandbyReporting_reportsUnknownNaN() throws Exception {
        // zero rows in pg_stat_replication → no standby attached
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        ReplicaLagMetrics metrics = new ReplicaLagMetrics(ds, replicaConfigured(), registry);

        metrics.refresh();

        assertThat(registry.get(ReplicaLagMetrics.METRIC_NAME).gauge().value()).isNaN();
    }

    @Test
    void probeFailure_degradesSilentlyToNaN() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("permission denied for pg_stat_replication"));
        ReplicaLagMetrics metrics = new ReplicaLagMetrics(dataSource, replicaConfigured(), registry);

        assertThatCode(metrics::refresh)
                .as("a permission-lacking probe must never break the scheduler thread")
                .doesNotThrowAnyException();

        assertThat(registry.get(ReplicaLagMetrics.METRIC_NAME).gauge().value()).isNaN();
    }

    @Test
    void nullReplayLag_reportsUnknownNaN() throws Exception {
        // standby attached but replay_lag not measured yet (NULL)
        stubProbe(0.0, true);
        ReplicaLagMetrics metrics = new ReplicaLagMetrics(dataSource, replicaConfigured(), registry);

        metrics.refresh();

        assertThat(registry.get(ReplicaLagMetrics.METRIC_NAME).gauge().value()).isNaN();
    }

    private void stubProbe(double lag, boolean wasNull) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDouble(1)).thenReturn(lag);
        when(resultSet.wasNull()).thenReturn(wasNull);
    }
}
