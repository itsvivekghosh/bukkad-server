package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.alerting")
public class AlertingProperties {

    private boolean enabled = true;
    private long dedupWindowSeconds = 60;
    private SlowRequest slowRequest = new SlowRequest();
    private HttpError httpError = new HttpError();
    private Webhook webhook = new Webhook();

    @Data
    public static class SlowRequest {
        private long warningThresholdMs = 1000;
        private long criticalThresholdMs = 3000;
    }

    @Data
    public static class HttpError {
        private boolean alertOn4xx = true;
        private boolean alertOn5xx = true;
    }

    @Data
    public static class Webhook {
        private boolean enabled = false;
        private String url = "";
        private String secret = "";
    }
}
