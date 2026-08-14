package com.bhukkad.cluster;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class InstanceMetadata {

    private final String instanceId;

    public InstanceMetadata() {
        this.instanceId = resolveInstanceId();
    }

    public String getInstanceId() {
        return instanceId;
    }

    private static String resolveInstanceId() {
        for (String envKey : List.of("POD_NAME", "HOSTNAME", "INSTANCE_ID")) {
            String value = System.getenv(envKey);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return UUID.randomUUID().toString();
    }
}
