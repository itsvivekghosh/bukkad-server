package com.bhukkad.admin.fraud;

import java.util.List;

/**
 * Result of {@link FraudRiskScoringService#scoreOrder(Long, double)}.
 *
 * @param scored  false when scoring is disabled — callers must treat as "no opinion"
 * @param score   0–100 risk score
 * @param level   coarse bucket derived from the configured thresholds
 * @param reasons human-readable feature flags that pushed the score up
 */
public record FraudRiskAssessment(boolean scored, int score, Level level, List<String> reasons) {

    public enum Level { LOW, MEDIUM, HIGH, CRITICAL }

    static FraudRiskAssessment noScore() {
        return new FraudRiskAssessment(false, 0, Level.LOW, List.of());
    }
}
