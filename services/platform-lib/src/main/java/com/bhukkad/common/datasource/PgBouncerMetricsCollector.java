package com.bhukkad.common.datasource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and exposes PgBouncer metrics via Micrometer.
 * <p>
 * Queries PgBouncer's admin console (SHOW STATS, SHOW POOLS, SHOW CLIENTS, etc.)
 * to expose real-time connection pooling metrics for 200k+ TPS monitoring.
 * </p>
 */
public class PgBouncerMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(PgBouncerMetricsCollector.class);

    private final DataSource writeDataSource;
    private final DataSource readDataSource;
    private final Map<String, HikariDataSource> replicaDataSources;
    private final MeterRegistry meterRegistry;
    private final PgBouncerProperties pgBouncerProperties;

    private final JdbcTemplate adminTemplate;
    private final Map<String, AtomicLong> poolStats = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> clientStats = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> replicaPoolStats = new ConcurrentHashMap<>();

    public PgBouncerMetricsCollector(DataSource writeDataSource,
                                      DataSource readDataSource,
                                      Map<String, HikariDataSource> replicaDataSources,
                                      MeterRegistry meterRegistry,
                                      PgBouncerProperties pgBouncerProperties) {
        this.writeDataSource = writeDataSource;
        this.readDataSource = readDataSource;
        this.replicaDataSources = replicaDataSources != null ? replicaDataSources : new HashMap<>();
        this.meterRegistry = meterRegistry;
        this.pgBouncerProperties = pgBouncerProperties;
        this.adminTemplate = createAdminTemplate();
    }

    private JdbcTemplate createAdminTemplate() {
        try {
            // Create a simple datasource for PgBouncer admin console
            // This connects to the pgbouncer database
            // We'll use the write datasource but query the pgbouncer admin functions
            return new JdbcTemplate(writeDataSource);
        } catch (Exception e) {
            log.warn("Could not create PgBouncer admin template: {}", e.getMessage());
            return null;
        }
    }

    @PostConstruct
    public void initMetrics() {
        if (!pgBouncerProperties.isEnabled() || meterRegistry == null) {
            return;
        }

        // Register gauges for pool statistics
        Gauge.builder("pgbouncer.pools.active_connections", this, c -> getPoolStat("active_connections"))
                .description("Active connections in PgBouncer pools")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.pools.waiting_requests", this, c -> getPoolStat("waiting_requests"))
                .description("Waiting requests in PgBouncer pools")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.pools.server_lifetime_avg", this, c -> getPoolStat("server_lifetime_avg"))
                .description("Average server connection lifetime in microseconds")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.clients.active", this, c -> getClientStat("active"))
                .description("Active client connections to PgBouncer")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.clients.waiting", this, c -> getClientStat("waiting"))
                .description("Waiting client connections to PgBouncer")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.clients.idle", this, c -> getClientStat("idle"))
                .description("Idle client connections to PgBouncer")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.clients.used", this, c -> getClientStat("used"))
                .description("Used client connections to PgBouncer")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.clients.tested", this, c -> getClientStat("tested"))
                .description("Tested client connections to PgBouncer")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.clients.login", this, c -> getClientStat("login"))
                .description("Client connections in login state")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.databases.total_connections", this, c -> getDbStat("total_connections"))
                .description("Total connections across all databases")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.databases.avg_query_time", this, c -> getDbStat("avg_query_time"))
                .description("Average query time in microseconds")
                .register(meterRegistry);

        Gauge.builder("pgbouncer.databases.avg_wait_time", this, c -> getDbStat("avg_wait_time"))
                .description("Average wait time in microseconds")
                .register(meterRegistry);

        // Replica pool metrics for 200k+ TPS monitoring
        for (Map.Entry<String, HikariDataSource> entry : replicaDataSources.entrySet()) {
            String replicaKey = entry.getKey();
            HikariDataSource hikari = entry.getValue();
            Gauge.builder("pgbouncer.replica.connections.active", hikari, h -> h.getHikariPoolMXBean().getActiveConnections())
                    .description("Active connections in PgBouncer replica pool")
                    .tag("replica", replicaKey)
                    .register(meterRegistry);
            Gauge.builder("pgbouncer.replica.connections.idle", hikari, h -> h.getHikariPoolMXBean().getIdleConnections())
                    .description("Idle connections in PgBouncer replica pool")
                    .tag("replica", replicaKey)
                    .register(meterRegistry);
            Gauge.builder("pgbouncer.replica.connections.pending", hikari, h -> h.getHikariPoolMXBean().getThreadsAwaitingConnection())
                    .description("Pending connection requests in PgBouncer replica pool")
                    .tag("replica", replicaKey)
                    .register(meterRegistry);
        }

        log.info("PgBouncer metrics collector initialized with {} replica pools", replicaDataSources.size());
    }

    @Scheduled(fixedRateString = "${app.datasource.pgbouncer.stats-interval:60000}")
    public void collectMetrics() {
        if (!pgBouncerProperties.isEnabled() || adminTemplate == null) {
            return;
        }

        try {
            collectPoolStats();
            collectClientStats();
            collectDatabaseStats();
            collectReplicaStats();
        } catch (Exception e) {
            log.debug("Failed to collect PgBouncer metrics: {}", e.getMessage());
        }
    }

    private void collectPoolStats() {
        try {
            var pools = adminTemplate.queryForList("SHOW POOLS");
            for (var pool : pools) {
                String database = (String) pool.get("database");
                String user = (String) pool.get("user");
                String key = database + "." + user;

                Long clActive = ((Number) pool.getOrDefault("cl_active", 0)).longValue();
                Long clWaiting = ((Number) pool.getOrDefault("cl_waiting", 0)).longValue();
                Long svActive = ((Number) pool.getOrDefault("sv_active", 0)).longValue();
                Long svIdle = ((Number) pool.getOrDefault("sv_idle", 0)).longValue();
                Long svUsed = ((Number) pool.getOrDefault("sv_used", 0)).longValue();
                Long svTested = ((Number) pool.getOrDefault("sv_tested", 0)).longValue();
                Long svLogin = ((Number) pool.getOrDefault("sv_login", 0)).longValue();
                Long maxwait = ((Number) pool.getOrDefault("maxwait", 0)).longValue();
                Long maxwaitUs = ((Number) pool.getOrDefault("maxwait_us", 0)).longValue();
                Long poolMode = ((Number) pool.getOrDefault("pool_mode", 0)).longValue();

                poolStats.computeIfAbsent(key + ".cl_active", k -> new AtomicLong()).set(clActive);
                poolStats.computeIfAbsent(key + ".cl_waiting", k -> new AtomicLong()).set(clWaiting);
                poolStats.computeIfAbsent(key + ".sv_active", k -> new AtomicLong()).set(svActive);
                poolStats.computeIfAbsent(key + ".sv_idle", k -> new AtomicLong()).set(svIdle);
                poolStats.computeIfAbsent(key + ".sv_used", k -> new AtomicLong()).set(svUsed);
                poolStats.computeIfAbsent(key + ".sv_tested", k -> new AtomicLong()).set(svTested);
                poolStats.computeIfAbsent(key + ".sv_login", k -> new AtomicLong()).set(svLogin);
                poolStats.computeIfAbsent(key + ".maxwait", k -> new AtomicLong()).set(maxwait);
                poolStats.computeIfAbsent(key + ".maxwait_us", k -> new AtomicLong()).set(maxwaitUs);
            }
        } catch (Exception e) {
            log.debug("Failed to collect pool stats: {}", e.getMessage());
        }
    }

    private void collectClientStats() {
        try {
            var clients = adminTemplate.queryForList("SHOW CLIENTS");
            long active = 0, waiting = 0, idle = 0, used = 0, tested = 0, login = 0;

            for (var client : clients) {
                String state = (String) client.get("state");
                switch (state) {
                    case "active" -> active++;
                    case "waiting" -> waiting++;
                    case "idle" -> idle++;
                    case "used" -> used++;
                    case "tested" -> tested++;
                    case "login" -> login++;
                }
            }

            clientStats.computeIfAbsent("active", k -> new AtomicLong()).set(active);
            clientStats.computeIfAbsent("waiting", k -> new AtomicLong()).set(waiting);
            clientStats.computeIfAbsent("idle", k -> new AtomicLong()).set(idle);
            clientStats.computeIfAbsent("used", k -> new AtomicLong()).set(used);
            clientStats.computeIfAbsent("tested", k -> new AtomicLong()).set(tested);
            clientStats.computeIfAbsent("login", k -> new AtomicLong()).set(login);

        } catch (Exception e) {
            log.debug("Failed to collect client stats: {}", e.getMessage());
        }
    }

    private void collectDatabaseStats() {
        try {
            var dbs = adminTemplate.queryForList("SHOW DATABASES");
            long totalConnections = 0;
            double totalQueryTime = 0;
            double totalWaitTime = 0;
            int count = 0;

            for (var db : dbs) {
                Long total = ((Number) db.getOrDefault("total_connections", 0)).longValue();
                Double avgQuery = ((Number) db.getOrDefault("avg_query_time", 0)).doubleValue();
                Double avgWait = ((Number) db.getOrDefault("avg_wait_time", 0)).doubleValue();

                totalConnections += total;
                totalQueryTime += avgQuery;
                totalWaitTime += avgWait;
                count++;
            }

            poolStats.computeIfAbsent("total_connections", k -> new AtomicLong()).set(totalConnections);
            if (count > 0) {
                poolStats.computeIfAbsent("avg_query_time", k -> new AtomicLong()).set((long) (totalQueryTime / count));
                poolStats.computeIfAbsent("avg_wait_time", k -> new AtomicLong()).set((long) (totalWaitTime / count));
            }

        } catch (Exception e) {
            log.debug("Failed to collect database stats: {}", e.getMessage());
        }
    }

    private void collectReplicaStats() {
        for (Map.Entry<String, HikariDataSource> entry : replicaDataSources.entrySet()) {
            String replicaKey = entry.getKey();
            HikariDataSource hikari = entry.getValue();
            try {
                var poolBean = hikari.getHikariPoolMXBean();
                replicaPoolStats.computeIfAbsent(replicaKey + ".active", k -> new AtomicLong()).set(poolBean.getActiveConnections());
                replicaPoolStats.computeIfAbsent(replicaKey + ".idle", k -> new AtomicLong()).set(poolBean.getIdleConnections());
                replicaPoolStats.computeIfAbsent(replicaKey + ".pending", k -> new AtomicLong()).set(poolBean.getThreadsAwaitingConnection());
            } catch (Exception e) {
                log.debug("Failed to collect replica stats for {}: {}", replicaKey, e.getMessage());
            }
        }
    }

    private double getPoolStat(String stat) {
        return poolStats.values().stream()
                .filter(k -> k.toString().endsWith("." + stat))
                .mapToLong(AtomicLong::get)
                .sum();
    }

    private double getClientStat(String stat) {
        AtomicLong value = clientStats.get(stat);
        return value != null ? value.get() : 0;
    }

    private double getDbStat(String stat) {
        AtomicLong value = poolStats.get(stat);
        return value != null ? value.get() : 0;
    }

    /**
     * Get current PgBouncer health status.
     */
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", pgBouncerProperties.isEnabled());

        if (!pgBouncerProperties.isEnabled()) {
            status.put("status", "DISABLED");
            return status;
        }

        try {
            if (adminTemplate != null) {
                var result = adminTemplate.queryForMap("SHOW SERVERS");
                status.put("status", "UP");
                status.put("servers", result);
            } else {
                status.put("status", "UNKNOWN");
            }
        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("error", e.getMessage());
        }
        return status;
    }
}