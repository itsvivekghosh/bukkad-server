package com.bhukkad.featureflag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime feature-flag store. Initial values come from
 * {@link FeatureFlagProperties}; operators can toggle flags in-process for
 * gradual rollouts and instant rollback without redeploys.
 */
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagProperties properties;
    private final Map<String, Boolean> runtimeOverrides = new ConcurrentHashMap<>();

    public boolean isEnabled(String key) {
        return runtimeOverrides.getOrDefault(key, properties.isEnabled(key));
    }

    /** Sets a runtime override. Pass {@code null} to revert to config value. */
    public void setFlag(String key, Boolean enabled) {
        if (enabled == null) {
            runtimeOverrides.remove(key);
        } else {
            runtimeOverrides.put(key, enabled);
        }
    }

    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> result = new ConcurrentHashMap<>();
        properties.getFlags().forEach(result::put);
        runtimeOverrides.forEach(result::put);
        return result;
    }
}
