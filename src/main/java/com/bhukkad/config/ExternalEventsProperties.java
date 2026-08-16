package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.events.external")
public class ExternalEventsProperties {
    private boolean enabled = false;
    /** log | kafka */
    private String type = "log";
    private Kafka kafka = new Kafka();

    public boolean isKafkaEnabled() {
        return enabled && "kafka".equalsIgnoreCase(type);
    }

    @Data
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String platformTopic = "bhukkad.platform.events";
        private String consumerGroup = "bhukkad-platform-consumer";
    }
}
