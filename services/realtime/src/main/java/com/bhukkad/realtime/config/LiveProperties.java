package com.bhukkad.realtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.live")
public class LiveProperties {

    private int maxEmittersPerStream = 50;
    private int maxTotalEmitters = 2000;
    private ReplayProperties replay = new ReplayProperties();

    @Data
    public static class ReplayProperties {
        private int ttlSeconds = 3600;
        private int maxEventsPerStream = 100;
    }
}
