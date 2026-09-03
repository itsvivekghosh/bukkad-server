package com.bhukkad.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


@Data

@ConfigurationProperties(prefix = "app.cache.stampede")
public class StampedeProperties {
    private boolean enabled = true;
    private int jitterPercent = 10;

    /**
     * Probabilistic early expiration: entries within this percentage of their
     * TTL may be treated as expired (with increasing probability as they near
     * the deadline). This spreads recomputation across the window instead of a
     * synchronized thundering herd at the exact expiry moment.
     */
    private int earlyExpirePercent = 5;
}