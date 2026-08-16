package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.referral")
public class ReferralProperties {
    private boolean enabled = true;
    private double bonusAmount = 50.0;
    private double refereeBonusAmount = 25.0;
}
