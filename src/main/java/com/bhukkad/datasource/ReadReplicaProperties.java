package com.bhukkad.datasource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Data
@ConfigurationProperties(prefix = "app.datasource.read-replica")
public class ReadReplicaProperties {

    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private Hikari hikari = new Hikari();

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(url);
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
