package com.bhukkad.growth.api.dto.response;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoyaltyPointsResponseTest {

    @Test
    void builderSetsAllFields() {
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
    void platinumTier() {
        LoyaltyPointsResponse response = LoyaltyPointsResponse.builder()
                .customerId(1L)
                .currentPoints(10000)
                .lifetimePoints(15000)
                .tierLevel(5)
                .tierName("Platinum")
                .pointsToNextTier(0)
                .build();

        assertEquals(5, response.getTierLevel());
        assertEquals("Platinum", response.getTierName());
        assertEquals(0, response.getPointsToNextTier());
    }

    @Test
    void noArgsConstructor() {
        LoyaltyPointsResponse response = new LoyaltyPointsResponse();

        assertNull(response.getCustomerId());
        assertNull(response.getTierName());
    }

    @Test
    void setters() {
        LoyaltyPointsResponse response = new LoyaltyPointsResponse();
        response.setCustomerId(5L);
        response.setCurrentPoints(250);
        response.setTierName("Gold");

        assertEquals(5L, response.getCustomerId());
        assertEquals(250, response.getCurrentPoints());
        assertEquals("Gold", response.getTierName());
    }
}
