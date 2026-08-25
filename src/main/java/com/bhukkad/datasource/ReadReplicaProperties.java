package com.bhukkad.datasource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.datasource.read-replica")
public class ReadReplicaProperties {

    private boolean enabled = false;

    /** Single-replica URL (legacy). Ignored when {@link #replicas} is non-empty. */
    private String url;
    private String username;
    private String password;
    private Hikari hikari = new Hikari();

    /**
     * Multiple read replicas for horizontal read scaling. When non-empty, one
     * HikariCP pool is created per entry and connections are round-robined
     * across them (see {@link ReadReplicaSelector}).
     */
    private List<Replica> replicas = new ArrayList<>();

    public boolean isConfigured() {
        return enabled && (StringUtils.hasText(url) || !replicas.isEmpty());
    }

    /** Whether multiple replicas are configured (as opposed to the legacy single URL). */
    public boolean hasMultipleReplicas() {
        return enabled && !replicas.isEmpty();
    }

    @Data
    public static class Replica {
        private String url;
        private String username;
        private String password;
        private Hikari hikari = new Hikari();
    }

    @Data
    public static class Hikari {
        private String poolName = "BhukkadReadReplicaPool";
        private int maximumPoolSize = 25;
        private int minimumIdle = 5;
        private long connectionTimeout = 30_000L;
        private long idleTimeout = 600_000L;
        private long maxLifetime = 1_800_000L;
        private boolean readOnly = true;
    }
}
