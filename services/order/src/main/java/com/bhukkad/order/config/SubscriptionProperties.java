package com.bhukkad.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the recurring subscription meal-plan scheduler.
 *
 * <p>Bound from {@code app.subscriptions.*}. Self-registering with
 * {@link Component} so it works both in production and in unit tests that
 * construct the scheduler without the full {@code @EnableConfigurationProperties}
 * configuration.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.subscriptions")
public class SubscriptionProperties {

    /** Master switch for the subscription scheduler. Defaults to off. */
    private boolean enabled = false;

    /** Scheduler interval between materialisation sweeps, in milliseconds. */
    private long schedulerIntervalMs = 3600000L;

    /** How far in advance (days) a due delivery may be materialised. */
    private int maxAdvanceDays = 7;
}
