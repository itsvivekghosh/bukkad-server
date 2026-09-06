package com.bhukkad.common.featureflag;

/**
 * Shared bucketing for percentage-based feature-flag rollouts.
 *
 * <p>Used by {@link FeatureFlagService} (servlet services) and the gateway edge
 * kill switch so both layers put a given user into the <em>same</em> bucket —
 * a service-side rollout and an edge rollout of one flag are consistent.</p>
 */
public final class FeatureFlagHash {

    private FeatureFlagHash() {
    }

    /** Stable 0–99 bucket for (key, subject); identical across JVM restarts. */
    public static int bucket(String key, Long subject) {
        long h = 1125899906842597L; // large prime seed
        String input = key + ":" + subject;
        for (char c : input.toCharArray()) {
            h = 31 * h + c;
        }
        return (int) Math.abs(h % 100);
    }

    /** Rollout decision: percent null → global; uid unknown → only 100 % activates. */
    public static boolean inRollout(String key, Long subject, Integer percent, boolean globalEnabled) {
        if (percent == null) {
            return globalEnabled;
        }
        if (!globalEnabled || subject == null || percent >= 100) {
            return globalEnabled;
        }
        if (percent <= 0) {
            return false;
        }
        return bucket(key, subject) < percent;
    }
}
