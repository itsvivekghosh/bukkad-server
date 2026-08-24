package com.bhukkad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.synthetic-health")
public class SyntheticHealthCheckProperties {
    /**
     * List of endpoints to check for synthetic health checks.
     * Each endpoint should be a path relative to the base URL of the application.
     */
    private List<String> endpoints = List.of(
            "/actuator/health",
            "/api/v1/serviceability"
    );

    /**
     * Interval in milliseconds between health check runs.
     */
    private long intervalMs = 60000; // 1 minute

    /**
     * Timeout in milliseconds for each HTTP request.
     */
    private long timeoutMs = 5000; // 5 seconds

    public List<String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<String> endpoints) {
        this.endpoints = endpoints;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}