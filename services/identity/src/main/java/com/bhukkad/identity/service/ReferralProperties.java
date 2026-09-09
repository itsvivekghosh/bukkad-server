package com.bhukkad.identity.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Port of the monolith {@code com.bhukkad.config.ReferralProperties} for the
 * identity service (WAVE 2). Bound from {@code app.referral.*}; defaults match
 * the monolith so zero-config deployments behave identically.
 */
@Data
@ConfigurationProperties(prefix = "app.referral")
public class ReferralProperties {
    private boolean enabled = true;
    private double bonusAmount = 50.0;
    private double refereeBonusAmount = 25.0;
}
