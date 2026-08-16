package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.monitoring")
public class MonitoringProperties {
    private Prometheus prometheus = new Prometheus();

    @Data
    public static class Prometheus {
        private boolean requireAuth = true;
        private String bearerToken = "";
    }
}
