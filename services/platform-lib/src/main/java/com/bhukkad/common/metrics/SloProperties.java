package com.bhukkad.common.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.slo")
public class SloProperties {

    private boolean enabled = false;
    private long latencyTargetMs = 500;
    private double availabilityTargetPercent = 99.5;
    private double burnRateWarning = 1.0;
    private double burnRateCritical = 2.0;
    private int windowMinutes = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getLatencyTargetMs() {
        return latencyTargetMs;
    }

    public void setLatencyTargetMs(long latencyTargetMs) {
        this.latencyTargetMs = latencyTargetMs;
    }

    public double getAvailabilityTargetPercent() {
        return availabilityTargetPercent;
    }

    public void setAvailabilityTargetPercent(double availabilityTargetPercent) {
        this.availabilityTargetPercent = availabilityTargetPercent;
    }

    public double getBurnRateWarning() {
        return burnRateWarning;
    }

    public void setBurnRateWarning(double burnRateWarning) {
        this.burnRateWarning = burnRateWarning;
    }

    public double getBurnRateCritical() {
        return burnRateCritical;
    }

    public void setBurnRateCritical(double burnRateCritical) {
        this.burnRateCritical = burnRateCritical;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }
}