package com.bhukkad.payment.settlement;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Automated settlement batch configuration (migration plan W2 / gap register G1).
 *
 * <p>Key names deliberately mirror the monolith's {@code app.settlement.*}
 * block ({@code SettlementProperties}) so the traffic flip is a config move,
 * not a rewrite. Unlike the monolith, the automation is <b>off by default</b>
 * until the settlement cutover ladder reaches it (rollback rule: "flag off +
 * scheduler off in payment + re-enable the monolith scheduler").</p>
 */
@Data
@ConfigurationProperties(prefix = "app.settlement")
public class SettlementAutomationProperties {

    /** Enable the automated settlement batch cron in this instance. */
    private boolean autoEnabled = false;

    /** Cron for the automated settlement run (default: daily 2 AM, parity with monolith). */
    private String autoSettleCron = "0 0 2 * * *";

    /** Minimum pending net amount (₹) before a restaurant's rows are auto-settled. */
    private BigDecimal minPendingAmount = new BigDecimal("100.00");

    /** Safety cap on restaurants processed per tick; the next tick resumes. */
    private int batchLimit = 500;
}
