package com.bhukkad.churn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for churn prediction and proactive retention (FEATURE #12).
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.churn")
public class ChurnProperties {

    /** Master switch — scoring job and retention actions are no-ops when false. */
    private boolean enabled = true;

    /** Cron for the weekly re-scoring sweep. */
    private String scoringCron = "0 0 3 * * MON";

    /**
     * Scores at or above this mark the customer as high-risk and trigger the
     * retention action.
     */
    private int retentionThreshold = 65;

    /** Maximum customers scored per run (bounded work per sweep). */
    private int batchSize = 500;

    /** Days of inactivity that indicate meaningful decay. */
    private int recencyDecayDays = 30;
}
