package com.bhukkad.growth.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrowthPropertiesTest {

    @Test
    void loyaltyPropertiesDefaults() {
        GrowthProperties.LoyaltyProperties loyalty = new GrowthProperties.LoyaltyProperties();

        assertEquals(1, loyalty.getPointsPerRupee());
        assertEquals(100, loyalty.getSignUpBonus());
        assertEquals(50, loyalty.getReferralBonus());
        assertEquals(100, loyalty.getRedemptionRatio());
        assertEquals(100, loyalty.getMinRedemptionPoints());
    }

    @Test
    void referralPropertiesDefaults() {
        GrowthProperties.ReferralProperties referral = new GrowthProperties.ReferralProperties();

        assertEquals(10, referral.getMaxReferralsPerUser());
        assertEquals(100, referral.getReferredUserReward());
        assertEquals(50, referral.getReferrerReward());
    }

    @Test
    void campaignPropertiesDefaults() {
        GrowthProperties.CampaignProperties campaign = new GrowthProperties.CampaignProperties();

        assertEquals(50, campaign.getMaxDiscountPercent());
        assertEquals(0, campaign.getDefaultPriority());
    }

    @Test
    void customValues() {
        GrowthProperties props = new GrowthProperties();

        GrowthProperties.LoyaltyProperties loyalty = new GrowthProperties.LoyaltyProperties();
        loyalty.setPointsPerRupee(2);
        loyalty.setSignUpBonus(200);
        loyalty.setReferralBonus(100);
        loyalty.setRedemptionRatio(50);
        loyalty.setMinRedemptionPoints(200);
        props.setLoyalty(loyalty);

        GrowthProperties.ReferralProperties referral = new GrowthProperties.ReferralProperties();
        referral.setMaxReferralsPerUser(5);
        referral.setReferredUserReward(200);
        referral.setReferrerReward(100);
        props.setReferral(referral);

        GrowthProperties.CampaignProperties campaign = new GrowthProperties.CampaignProperties();
        campaign.setMaxDiscountPercent(30);
        campaign.setDefaultPriority(5);
        props.setCampaign(campaign);

        assertEquals(2, props.getLoyalty().getPointsPerRupee());
        assertEquals(200, props.getLoyalty().getSignUpBonus());
        assertEquals(5, props.getReferral().getMaxReferralsPerUser());
        assertEquals(30, props.getCampaign().getMaxDiscountPercent());
    }
}
