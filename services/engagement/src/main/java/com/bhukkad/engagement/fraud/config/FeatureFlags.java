package com.bhukkad.engagement.fraud.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unified feature flag configuration for the Engagement Service.
 * 
 * <p>Provides centralized feature flag management with dynamic override
 * support via Redis for safe gradual rollouts and A/B testing.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.features")
@Getter
@Setter
public class FeatureFlags {

    // Fraud engine flags
    private boolean fraudEngineEnabled = true;
    private boolean fraudCacheStampedePrevention = true;
    private boolean fraudBatchDuplicateDetection = true;

    // Loyalty flags
    private boolean loyaltyCacheEnabled = true;
    private boolean loyaltyCacheStampedePrevention = true;
    private boolean loyaltyTierCacheEnabled = true;

    // Feed flags
    private boolean feedL1CacheEnabled = true;
    private boolean feedBatchRestaurantEnrichment = true;
    private boolean feedSegmentIncrementalRebuild = true;

    // Social flags
    private boolean likeCounterPipelineEnabled = true;
    private boolean likeSyncDeduplication = true;

    // Notification flags
    private boolean notificationAsyncDispatch = true;
    private boolean notificationCircuitBreaker = true;

    // Referral flags
    private boolean referralRedisCollisionCache = true;

    // SSE flags
    private boolean sseBatchHeartbeats = true;
    private boolean sseLazyStreamCleanup = true;
    private boolean sseAdaptiveBackpressure = true;

    // Real-time flags
    private boolean eventSourcingEnabled = false; // Gradual rollout
    private boolean loyaltyCqrsEnabled = false;   // Gradual rollout

    // Rollout percentages for gradual release (0-100)
    private Map<String, Integer> rolloutPercentages = new HashMap<>();

    /**
     * Checks if a feature is enabled, considering rollout percentage.
     */
    public boolean isEnabled(String feature) {
        boolean enabled = getFlagValue(feature);
        if (!enabled) {
            return false;
        }
        // Check rollout percentage for gradual release
        if (rolloutPercentages.containsKey(feature)) {
            int percentage = rolloutPercentages.get(feature);
            return ThreadLocalRandom.current().nextInt(100) < percentage;
        }
        return true;
    }

    private boolean getFlagValue(String feature) {
        try {
            return (boolean) FeatureFlags.class
                    .getDeclaredField(feature)
                    .get(this);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sets a feature flag dynamically (for runtime overrides).
     */
    public void setFlag(String feature, boolean enabled) {
        try {
            FeatureFlags.class.getDeclaredField(feature).set(this, enabled);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown feature flag: " + feature, e);
        }
    }

    /**
     * Returns all feature flags as a map for monitoring/debugging.
     */
    public Map<String, Boolean> getAllFlags() {
        Map<String, Boolean> flags = new HashMap<>();
        for (java.lang.reflect.Field field : FeatureFlags.class.getDeclaredFields()) {
            if (field.getType() == boolean.class) {
                try {
                    flags.put(field.getName(), field.getBoolean(this));
                } catch (Exception e) {
                    // Skip inaccessible fields
                }
            }
        }
        return flags;
    }
}
