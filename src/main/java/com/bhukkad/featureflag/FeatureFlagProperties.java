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
 */
@Data
@ConfigurationProperties(prefix = "app.feature-flags")
public class FeatureFlagProperties {

    private Map<String, Boolean> flags = new HashMap<>();

    public boolean isEnabled(String key) {
        return flags.getOrDefault(key, false);
    }
}
