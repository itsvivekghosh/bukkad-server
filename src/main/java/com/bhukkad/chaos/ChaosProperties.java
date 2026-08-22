package com.bhukkad.chaos;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chaos/fault-injection configuration. All knobs default to disabled so the
 * application behaves normally in production unless explicitly turned on for
 * resilience testing.
 */
@Data
@ConfigurationProperties(prefix = "app.chaos")
public class ChaosProperties {

    private boolean enabled = false;
    /** Per-request probability of injecting artificial latency. */
    private double latencyProbability = 0.0;
    /** Artificial latency in ms when triggered. */
    private long latencyMs = 0;
    /** Per-request probability of failing the call. */
    private double failureProbability = 0.0;

    public boolean shouldInjectLatency() {
        return enabled && latencyProbability > 0 && latencyMs > 0
                && Math.random() < latencyProbability;
    }

    public boolean shouldInjectFailure() {
        return enabled && failureProbability > 0 && Math.random() < failureProbability;
    }
}
