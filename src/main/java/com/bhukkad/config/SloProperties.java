package com.bhukkad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.slo")
public class SloProperties {
    /**
     * Target latency for successful requests (in milliseconds).
     * Requests below this latency are considered good for latency SLO.
     */
    private long targetLatencyMs = 500;

    /**
     * Target availability percentage (e.g., 99.9 for 99.9%).
     * Requests that are successful (non-5xx) are considered good for availability SLO.
     */
    private double targetAvailabilityPercentage = 99.9;

    /**
     * Evaluation window in minutes for calculating the SLO.
     * For example, 60 means we look at the last 60 minutes.
     */
    private int evaluationWindowMinutes = 60;

    /**
     * Alert if the burn rate exceeds this threshold.
     * Burn rate = (bad events) / (total events * (1 - SLO target))
     * For example, if target availability is 99.9%, then bad events allowed are 0.1%.
     * If we see more bad events than allowed, the burn rate > 1.
     * We alert when burn rate > alertBurnRateThreshold.
     */
    private double alertBurnRateThreshold = 1.0;

    public long getTargetLatencyMs() {
        return targetLatencyMs;
    }

    public void setTargetLatencyMs(long targetLatencyMs) {
        this.targetLatencyMs = targetLatencyMs;
    }

    public double getTargetAvailabilityPercentage() {
        return targetAvailabilityPercentage;
    }

    public void setTargetAvailabilityPercentage(double targetAvailabilityPercentage) {
        this.targetAvailabilityPercentage = targetAvailabilityPercentage;
    }

    public int getEvaluationWindowMinutes() {
        return evaluationWindowMinutes;
    }

    public void setEvaluationWindowMinutes(int evaluationWindowMinutes) {
        this.evaluationWindowMinutes = evaluationWindowMinutes;
    }

    public double getAlertBurnRateThreshold() {
        return alertBurnRateThreshold;
    }

    public void setAlertBurnRateThreshold(double alertBurnRateThreshold) {
        this.alertBurnRateThreshold = alertBurnRateThreshold;
    }
}