package com.bhukkad.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the ADR-002 search reconciliation sweep (search V10). Getters
 * are explicit — this module compiles without Lombok annotation processing.
 */
@ConfigurationProperties(prefix = "app.search.sync")
public class SearchSyncProperties {

    /** Master switch for the periodic sweep (event-driven sync is unaffected). */
    private boolean enabled = true;

    /** Sweep cadence. */
    private long intervalMs = 300_000;

    /** Restaurants reconciled per cycle (bounded batches). */
    private int restaurantsPerCycle = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getRestaurantsPerCycle() {
        return restaurantsPerCycle;
    }

    public void setRestaurantsPerCycle(int restaurantsPerCycle) {
        this.restaurantsPerCycle = restaurantsPerCycle;
    }
}
