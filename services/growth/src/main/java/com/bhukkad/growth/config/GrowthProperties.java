package com.bhukkad.growth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.growth")
public class GrowthProperties {

    private LoyaltyProperties loyalty = new LoyaltyProperties();
    private ReferralProperties referral = new ReferralProperties();
    private CampaignProperties campaign = new CampaignProperties();

    @Data
    public static class LoyaltyProperties {
        private int pointsPerRupee = 1;
        private int signUpBonus = 100;
        private int referralBonus = 50;
        private int redemptionRatio = 100; // 100 points = 1 rupee
        private int minRedemptionPoints = 100;
    }

    @Data
    public static class ReferralProperties {
        private int maxReferralsPerUser = 10;
        private int referredUserReward = 100;
        private int referrerReward = 50;
    }

    @Data
    public static class CampaignProperties {
        private int maxDiscountPercent = 50;
        private int defaultPriority = 0;
    }
}
