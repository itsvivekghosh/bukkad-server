package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.settlement")
public class SettlementProperties {
    private double commissionPercent = 15.0;
    /** Enable automated settlement batch runs. */
    private boolean autoSettleEnabled = true;
    /** Minimum pending amount (₹) before auto-settling a restaurant. */
    private double minPendingAmount = 100.0;
    /** Cron for automated settlement (default: daily 2 AM). */
    private String autoSettleCron = "0 0 2 * * *";
}
