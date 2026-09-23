package com.bhukkad.common.datasource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for PgBouncer connection pooling.
 * <p>
 * PgBouncer acts as a lightweight connection pooler in front of PostgreSQL,
 * enabling 10k+ client connections to be multiplexed over 200-500 actual
 * database connections. This is essential for 200k+ TPS workloads where
 * HikariCP alone would exhaust PostgreSQL's max_connections.
 * </p>
 *
 * <p>Transaction pooling mode (default) is recommended for most OLTP workloads
 * as it releases connections back to the pool at transaction boundaries.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.datasource.pgbouncer")
public class PgBouncerProperties {

    /** Enable PgBouncer integration. When true, datasources will connect to PgBouncer instead of PostgreSQL directly. */
    private boolean enabled = false;

    /** PgBouncer host (container name in Docker, or external IP). */
    private String host = "pgbouncer";

    /** PgBouncer port (default 6432). */
    private int port = 6432;

    /** Database name to connect to via PgBouncer. */
    private String database = "core";

    /** Username for PgBouncer authentication. */
    private String username = "app";

    /** Password for PgBouncer authentication. */
    private String password = "";

    /** Pool mode: transaction (default), session, or statement. Transaction mode is optimal for 200k+ TPS. */
    private String poolMode = "transaction";

    /** Maximum client connections PgBouncer will accept. */
    private int maxClientConn = 10000;

    /** Default pool size per database/user pair. */
    private int defaultPoolSize = 500;

    /** Minimum pool size. */
    private int minPoolSize = 100;

    /** Reserve pool size for superuser connections. */
    private int reservePoolSize = 10;

    /** Reserve pool timeout in seconds. */
    private int reservePoolTimeout = 5;

    /** Maximum connection lifetime in seconds. */
    private int maxConnectionLifetime = 3600;

    /** Idle timeout in seconds. */
    private int idleTimeout = 600;

    /** Query timeout in seconds (0 = no limit). */
    private int queryTimeout = 30;

    /** Query wait timeout in seconds. */
    private int queryWaitTimeout = 120;

    /** Enable PgBouncer stats tracking. */
    private boolean statsPeriod = true;

    /** Stats collection interval in seconds. */
    private int statsInterval = 60;

    /** Admin users who can access PgBouncer admin console. */
    private List<String> adminUsers = List.of("admin");

    /** PgBouncer log file path. */
    private String logFile = "/var/log/pgbouncer/pgbouncer.log";

    /** PgBouncer pid file path. */
    private String pidFile = "/var/run/pgbouncer/pgbouncer.pid";

    /** Unix socket directory for local connections. */
    private String unixSocketDir = "/var/run/postgresql";

    /** Enable TLS for PgBouncer connections. */
    private boolean tlsEnabled = false;

    /** Client TLS CA file. */
    private String tlsCaFile = "/etc/pgbouncer/tls/ca.crt";

    /** Client TLS cert file. */
    private String tlsCertFile = "/etc/pgbouncer/tls/server.crt";

    /** Client TLS key file. */
    private String tlsKeyFile = "/etc/pgbouncer/tls/server.key";

    /** Build JDBC URL for connecting via PgBouncer. */
    public String buildJdbcUrl() {
        StringBuilder url = new StringBuilder();
        url.append("jdbc:postgresql://");
        url.append(host).append(":").append(port).append("/");
        url.append(database);
        url.append("?user=").append(username);
        url.append("&password=").append(password);
        // PgBouncer-specific settings for high throughput
        url.append("&poolMode=").append(poolMode);
        url.append("&tcpKeepAlive=true");
        url.append("&reWriteBatchedInserts=true");
        url.append("&preferQueryMode=extended");
        return url.toString();
    }

    /** Build admin JDBC URL for PgBouncer admin console. */
    public String buildAdminJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/pgbouncer?user=admin", host, port);
    }
}