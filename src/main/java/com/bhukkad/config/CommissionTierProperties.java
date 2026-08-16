package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.commission")
public class CommissionTierProperties {
    private double defaultRate = 15.0;
    private List<Tier> tiers;

    @Data
    public static class Tier {
        private String name;
        private double minOrders;
        private double maxOrders;
        private double commissionPercent;
        private String description;
    }
}