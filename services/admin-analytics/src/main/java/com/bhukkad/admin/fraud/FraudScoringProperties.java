package com.bhukkad.admin.fraud;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Model configuration for {@link FraudRiskScoringService} (FEATURE #10).
 *
 * <p>The shipped coefficients are sensible defaults; they can be re-tuned from
 * production fraud_events data without a redeploy. Each feature contributes
 * {@code weight × featureValue} to a linear score which is squashed through a
 * logistic curve to 0–100.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.fraud-scoring")
public class FraudScoringProperties {

    private boolean enabled = true;

    /**
     * Enforcement switch. When false (default during rollout) high-risk orders are
     * only logged/persisted for review — never rejected. Flip on once observed
     * score distributions justify turning customers away.
     */
    private boolean enforcementEnabled = false;

    /** Score at or above this triggers a manual-review flag. */
    private int reviewThreshold = 60;

    /** Score at or above this auto-rejects the order when enforcementEnabled. */
    private int rejectThreshold = 85;

    private Map<String, Double> weights = defaultWeights();

    public double weight(String feature) {
        return weights.getOrDefault(feature, 0.0);
    }

    private static Map<String, Double> defaultWeights() {
        Map<String, Double> w = new LinkedHashMap<>();
        // Orders placed by this customer in the last 24h beyond the first (normalized).
        w.put("velocity24h", 2.2);
        // Account younger than one day.
        w.put("brandNewAccount", 1.6);
        // Account younger than seven days (weaker than brandNewAccount; both may apply).
        w.put("youngAccount", 0.8);
        // Prior fraud events recorded for this customer in the last 30 days (normalized).
        w.put("priorFraudEvents", 2.8);
        // Order placed between 00:00 and 05:00 local time — classic account-takeover window.
        w.put("lateNightHour", 1.1);
        // First order with an unusually large ticket (> 3× platform average).
        w.put("largeFirstOrder", 1.9);
        return w;
    }
}
