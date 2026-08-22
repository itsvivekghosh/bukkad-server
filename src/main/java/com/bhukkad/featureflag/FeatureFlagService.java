package com.bhukkad.featureflag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime feature-flag store. Initial values come from
 * {@link FeatureFlagProperties}; operators can toggle flags in-process for
 * gradual rollouts and instant rollback without redeploys.
 *
 * <p>Supports two activation modes per flag:</p>
 * <ul>
 *   <li><b>Boolean switch</b> — {@code isEnabled(key)} returns the configured
 *       or overridden value for every caller.</li>
 *   <li><b>Percentage rollout</b> — when a flag has a {@code rollout} percentage
 *       (0–100), {@code isEnabled(key, userId)} enables it deterministically for
 *       a fraction of users via a stable hash of the user id. This lets a flag
 *       ship to 5% of users and grow to 100% without code changes.</li>
 * </ul>
 *
 * <p>Every runtime toggle is audit-logged (who/what/when) so a kill-switch flip
 * is traceable in production.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagProperties properties;
    private final Map<String, Boolean> runtimeOverrides = new ConcurrentHashMap<>();

    /** Global flag check (no user context). */
    public boolean isEnabled(String key) {
        return runtimeOverrides.getOrDefault(key, properties.isEnabled(key));
    }

    /**
     * User-scoped flag check. When the flag has a configured rollout percentage,
     * a stable hash of the user id decides activation; otherwise falls back to
     * the global boolean switch.
     */
    public boolean isEnabled(String key, Long userId) {
        Integer rolloutPercent = properties.getRolloutPercent(key);
        if (rolloutPercent == null) {
            return isEnabled(key);
        }
        boolean global = isEnabled(key);
        if (!global || userId == null || rolloutPercent >= 100) {
            return global;
        }
        if (rolloutPercent <= 0) {
            return false;
        }
        return (stableHash(key, userId) % 100) < rolloutPercent;
    }

    /**
     * Sets a runtime override. Pass {@code null} to revert to config value.
     * Logs the change for the kill-switch audit trail.
     */
    public void setFlag(String key, Boolean enabled) {
        Boolean previous = runtimeOverrides.get(key);
        if (enabled == null) {
            runtimeOverrides.remove(key);
        } else {
            runtimeOverrides.put(key, enabled);
        }
        log.info("FEATURE_FLAG_CHANGED | key={} | from={} | to={}", key, previous, enabled);
    }

    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        properties.getFlags().forEach(result::put);
        runtimeOverrides.forEach(result::put);
        return result;
    }

    /**
     * Deterministic, stable hash of (flagKey, userId) so the same user always
     * sees the same flag state for a given percentage (no flapping between
     * requests).
     */
    private int stableHash(String key, Long userId) {
        long h = 1125899906842597L; // large prime seed
        String input = key + ":" + userId;
        for (char c : input.toCharArray()) {
            h = 31 * h + c;
        }
        return (int) Math.abs(h % 100);
    }
}
