package com.bhukkad.cluster;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.cluster")
public class ClusterProperties {

    private LiveRelay liveRelay = new LiveRelay();

    @Data
    public static class LiveRelay {
        /**
         * When enabled, order live updates are published to Redis so every app instance
         * can push to its local WebSocket/SSE subscribers (horizontal scaling).
         */
        private boolean enabled = true;
        private String channel = "bhukkad:live:order-updates";
    }
}
