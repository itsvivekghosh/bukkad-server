package com.bhukkad.referral.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Referral service settings.
 */
@ConfigurationProperties(prefix = "app.referral")
public class ReferralServiceProperties {

    /** Max collision-safe generation attempts before failing (no modulo fallback). */
    private int codeGenerationAttempts = 25;

    /** Reward (display ledger) credited to the referrer when a referral binds. */
    private double applyBonusAmount = 50.0;

    /** Reward (display ledger) credited to the referrer on referral completion. */
    private double completionBonusAmount = 25.0;

    public int getCodeGenerationAttempts() {
        return codeGenerationAttempts;
    }

    public void setCodeGenerationAttempts(int codeGenerationAttempts) {
        this.codeGenerationAttempts = codeGenerationAttempts;
    }

    public double getApplyBonusAmount() {
        return applyBonusAmount;
    }

    public void setApplyBonusAmount(double applyBonusAmount) {
        this.applyBonusAmount = applyBonusAmount;
    }

    public double getCompletionBonusAmount() {
        return completionBonusAmount;
    }

    public void setCompletionBonusAmount(double completionBonusAmount) {
        this.completionBonusAmount = completionBonusAmount;
    }
}
