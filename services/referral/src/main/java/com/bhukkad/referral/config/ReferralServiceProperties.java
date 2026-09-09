package com.bhukkad.referral.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Referral service settings.
 */
@ConfigurationProperties(prefix = "app.referral")
public class ReferralServiceProperties {

    /** Max code-generation attempts before falling back to the timestamp suffix. */
    private int codeGenerationAttempts = 5;

    public int getCodeGenerationAttempts() {
        return codeGenerationAttempts;
    }

    public void setCodeGenerationAttempts(int codeGenerationAttempts) {
        this.codeGenerationAttempts = codeGenerationAttempts;
    }
}