package com.bhukkad.common.datasource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

/**
 * V-17 replica lag gauge: {@code bhukkad.replica.lag} (Prometheus
 * {@code bhukkad_replica_lag_seconds}) — the worst standby replay lag in
 * seconds, read from {@code pg_stat_replication} on the primary.
 *
 * <p>The gauge exists <strong>only when a replica is configured</strong>
 * ({@code app.datasource.read-replica.*}); on a single-DB deployment nothing
 * is registered. The value degrades silently: when no standby is attached
 * (row absent / {@code replay_lag} NULL — a standby that has not yet reported)
 * or the probe fails (e.g. a pool user without rights on
 * {@code pg_stat_replication}), the gauge is set to NaN, which the Prometheus
 * registry omits, instead of reporting a misleading 0. Probe failures are
 * logged at WARN on the refresh cadence, never thrown.</p>
 *
 * <p>Refreshed on a fixed schedule (not per scrape) so scraping never opens a
 * DB connection — same pattern as {@code OutboxMetrics}. The probe runs on the
 * primary: the injected {@link DataSource} is the routing datasource, which
 * resolves to the primary pool for unqualified lookups on a scheduler thread
 * (no replica context, no read-only transaction).</p>
 *
 * <p>k8s alert wiring for this metric lands in the ops batch — alert on
 * {@code bhukkad_replica_lag_seconds} (absent == unknown/probe failure), with
 * the V-17 write-fence TTL
 * ({@code app.datasource.replica.write-fence-ms}) sized above the observed
 * lag.</p>
 */
@Slf4j
@Component
public class ReplicaLagMetrics {

    /** Metric name in the Micrometer convention (Prometheus: bhukkad_replica_lag_seconds). */
    public static final String METRIC_NAME = "bhukkad.replica.lag";

    private static final String REPLAY_LAG_SECONDS_SQL =
            "SELECT MAX(EXTRACT(EPOCH FROM replay_lag)) FROM pg_stat_replication";

    private final DataSource dataSource;
    private final boolean replicaConfigured;
    /** NaN = unknown (no standby reported / probe failed) → omitted by Prometheus. */
    private final AtomicReference<Double> lagSeconds = new AtomicReference<>(Double.NaN);

    public ReplicaLagMetrics(DataSource dataSource,
                             ReadReplicaProperties replicaProperties,
                             MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.replicaConfigured = replicaProperties.isConfigured();
        if (replicaConfigured) {
            Gauge.builder(METRIC_NAME, lagSeconds, ref -> ref.get() == null ? Double.NaN : ref.get())
                    .description("PostgreSQL streaming replica replay lag in seconds (pg_stat_replication, worst standby)")
                    .baseUnit("seconds")
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${app.datasource.replica.lag-refresh-ms:30000}")
    public void refresh() {
        if (!replicaConfigured) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(REPLAY_LAG_SECONDS_SQL)) {
            if (resultSet.next()) {
                double lag = resultSet.getDouble(1);
                lagSeconds.set(resultSet.wasNull() ? Double.NaN : lag);
            } else {
                lagSeconds.set(Double.NaN);
            }
        } catch (SQLException | RuntimeException ex) {
            // Degrade silently: unknown > wrong. The write fence bounds the
            // read-your-writes risk while this gauge is unavailable.
            lagSeconds.set(Double.NaN);
            log.warn("REPLICA_LAG_PROBE_FAILED | error={}", ex.getMessage());
        }
    }
}
