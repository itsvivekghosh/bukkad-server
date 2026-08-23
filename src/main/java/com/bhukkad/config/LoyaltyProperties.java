package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.loyalty")
public class LoyaltyProperties {
    private boolean enabled = false;
    private int pointsPerRupee = 100;
    private int maxRedemptionPercent = 20;
    private double minOrderAmount = 100.0;
}
