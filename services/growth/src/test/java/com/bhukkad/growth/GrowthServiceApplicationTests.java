package com.bhukkad.growth;

import com.bhukkad.growth.api.dto.response.LoyaltyPointsResponse;
import com.bhukkad.growth.api.dto.response.ReferralStatsResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrowthServiceApplicationTests {

    @Test
    void loyaltyPointsResponseBuilderWorks() {
        LoyaltyPointsResponse response = LoyaltyPointsResponse.builder()
                .customerId(1L)
                .currentPoints(500)
                .lifetimePoints(1500)
                .tierLevel(3)
                .tierName("Silver")
                .pointsToNextTier(500)
                .build();

        assertEquals(1L, response.getCustomerId());
        assertEquals(500, response.getCurrentPoints());
        assertEquals(1500, response.getLifetimePoints());
        assertEquals(3, response.getTierLevel());
        assertEquals("Silver", response.getTierName());
        assertEquals(500, response.getPointsToNextTier());
    }

    @Test
    void referralStatsResponseBuilderWorks() {
        ReferralStatsResponse response = ReferralStatsResponse.builder()
                .customerId(1L)
                .totalReferrals(10)
                .successfulReferrals(5)
                .pendingReferrals(2)
                .referrerRewardEarned(250)
                .referredUserRewardEarned(500)
                .build();

        assertEquals(1L, response.getCustomerId());
        assertEquals(10, response.getTotalReferrals());
        assertEquals(5, response.getSuccessfulReferrals());
        assertEquals(250, response.getReferrerRewardEarned());
    }
}
