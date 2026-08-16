package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.dynamic-pricing")
public class DynamicPricingProperties {
    private double minPrice = 10.0;
    private double maxSurgePercent = 50.0;
    private int maxRulesPerRestaurant = 10;
}