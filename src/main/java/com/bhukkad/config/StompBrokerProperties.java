package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.stomp.broker")
public class StompBrokerProperties {

    private BrokerType type = BrokerType.SIMPLE;
    private Rabbit rabbit = new Rabbit();

    public enum BrokerType {
        SIMPLE,
        RABBITMQ
    }

    @Data
    public static class Rabbit {
        private String host = "localhost";
        private int port = 61613;
        private String username = "guest";
        private String password = "guest";
        private String virtualHost = "/";
    }
}
