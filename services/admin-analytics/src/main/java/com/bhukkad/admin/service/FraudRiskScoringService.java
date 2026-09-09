package com.bhukkad.admin.service;

import org.springframework.stereotype.Service;

/**
 * Rule-based fraud risk scoring (port of monolith {@code FraudRiskScoringService}).
 * Deterministic score 0..1 from input flags.
 */
@Service
public class FraudRiskScoringService {

    public double score(boolean highFrequencyOrders, boolean suspiciousAddress, boolean fastRefundHistory,
                        int recentDisputes) {
        double score = 0.0;
        if (highFrequencyOrders) score += 0.3;
        if (suspiciousAddress) score += 0.3;
        if (fastRefundHistory) score += 0.2;
        score += Math.min(recentDisputes, 3) * 0.1;
        return Math.min(1.0, score);
    }

    public String severity(double score) {
        if (score >= 0.7) return "HIGH";
        if (score >= 0.4) return "MEDIUM";
        return "LOW";
    }
}