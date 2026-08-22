package com.bhukkad.featureflag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight config-driven feature flags. Flags default to the configured
 * value and can be overridden at runtime (in-process) via
 * {@link FeatureFlagService} — useful for gradual rollouts without an
 * external SaaS dependency.
 *
 * <p>The {@link #rollout} map allows percentage-based rollout per flag: when
 * present, the flag is enabled for a user if the deterministic hash of their
 * id falls within the configured percentage. This is more granular than the
 * boolean {@link #flags} switch and is the recommended approach for canary
 * releases.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.feature-flags")
public class FeatureFlagProperties {

    private Map<String, Boolean> flags = new HashMap<>();

    /** Percentage-based rollout per flag (0-100). Takes precedence over flags. */
    private Map<String, Integer> rollout = new HashMap<>();

    public boolean isEnabled(String key) {
        return flags.getOrDefault(key, false);
    }

    /** Returns the rollout percentage for a flag, or null if not configured. */
    public Integer getRolloutPercent(String key) {
        return rollout.get(key);
    }
}
