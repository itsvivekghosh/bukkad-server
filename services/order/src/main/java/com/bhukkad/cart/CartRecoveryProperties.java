package com.bhukkad.cart;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the abandoned-cart recovery sweep (FEATURE: recovery).
 *
 * <p>Bound from {@code app.cart.recovery.*}. Self-registering with
 * {@link Component} rather than being listed in an
 * {@code @EnableConfigurationProperties} block, matching the convention used
 * by the other domain properties classes such as
 * {@code com.bhukkad.admin.service.ChurnService} (admin-analytics).</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cart.recovery")
public class CartRecoveryProperties {

    /** Master switch for the recovery scheduler and its service. Defaults to off. */
    private boolean enabled = false;

    /** Minutes a cart must sit untouched before it counts as abandoned. */
    private int idleMinutes = 45;

    /** Percentage discount granted by the one-time recovery coupon. */
    private double couponPercent = 10.0;

    /** Minimum order value (₹) the recovery coupon applies to. */
    private double couponMinOrder = 200.0;

    /** Days the recovery coupon stays valid. Also the dedup window for a cart. */
    private int couponValidityDays = 3;

    /** Scheduler interval between recovery sweeps, in milliseconds. */
    private long intervalMs = 600000L;
}
