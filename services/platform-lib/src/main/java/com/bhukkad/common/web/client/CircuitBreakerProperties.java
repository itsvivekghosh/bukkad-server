package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-downstream circuit breaker configuration.
 *
 * <p>Each downstream service gets an isolated circuit breaker with
 * failure-rate thresholds tuned to its SLA. Failures on one downstream
 * never cascade to another downstream.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.circuit-breaker")
public class CircuitBreakerProperties {

    private Map<String, DownstreamConfig> instances = new LinkedHashMap<>();

    public Map<String, DownstreamConfig> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, DownstreamConfig> instances) {
        this.instances = instances;
    }

    public record DownstreamConfig(
            Float failureRateThreshold,
            Integer slidingWindowSize,
            Long waitDurationInOpenStateSeconds,
            Integer permittedNumberOfCallsInHalfOpenState,
            Duration slowCallDurationThreshold,
            Float slowCallRateThreshold
    ) {
        public DownstreamConfig() {
            this(
                50f,
                50,
                30L,
                3,
                Duration.ofSeconds(2),
                100f
            );
        }
    }

    public CircuitBreakerConfig toCircuitBreakerConfig(String name) {
        DownstreamConfig cfg = instances.getOrDefault(name, new DownstreamConfig());
        return CircuitBreakerConfig.custom()
            .failureRateThreshold(cfg.failureRateThreshold())
            .slidingWindowSize(cfg.slidingWindowSize())
            .slowCallDurationThreshold(cfg.slowCallDurationThreshold())
            .slowCallRateThreshold(cfg.slowCallRateThreshold())
            .waitDurationInOpenState(Duration.ofSeconds(cfg.waitDurationInOpenStateSeconds()))
            .permittedNumberOfCallsInHalfOpenState(cfg.permittedNumberOfCallsInHalfOpenState())
            .build();
    }
}
