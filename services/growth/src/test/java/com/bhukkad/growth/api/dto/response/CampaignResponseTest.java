package com.bhukkad.growth.api.dto.response;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CampaignResponseTest {

    @Test
    void builderSetsAllFields() {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(7);

        CampaignResponse response = CampaignResponse.builder()
                .id(1L)
                .name("Summer Sale")
                .campaignType("PERCENTAGE")
                .description("20% off on all orders")
                .discountPercent(20.0)
                .flatDiscountAmount(null)
                .minOrderAmount(100.0)
                .maxDiscountAmount(500.0)
                .freeDelivery(true)
                .priority(1)
                .isActive(true)
                .startsAt(start)
                .endsAt(end)
                .buyQuantity(2)
                .getQuantity(1)
                .getDiscountPercent(50)
                .targetSegment("NEW_USERS")
                .build();

        assertEquals(1L, response.getId());
        assertEquals("Summer Sale", response.getName());
        assertEquals("PERCENTAGE", response.getCampaignType());
        assertEquals("20% off on all orders", response.getDescription());
        assertEquals(20.0, response.getDiscountPercent());
        assertEquals(100.0, response.getMinOrderAmount());
        assertEquals(500.0, response.getMaxDiscountAmount());
        assertTrue(response.getFreeDelivery());
        assertEquals(1, response.getPriority());
        assertTrue(response.getIsActive());
        assertEquals(start, response.getStartsAt());
        assertEquals(end, response.getEndsAt());
        assertEquals(2, response.getBuyQuantity());
        assertEquals(1, response.getGetQuantity());
        assertEquals(50, response.getGetDiscountPercent());
        assertEquals("NEW_USERS", response.getTargetSegment());
    }

    @Test
    void noArgsConstructor() {
        CampaignResponse response = new CampaignResponse();

        assertNull(response.getId());
        assertNull(response.getName());
        assertNull(response.getIsActive());
    }

    @Test
    void setters() {
        CampaignResponse response = new CampaignResponse();
        response.setId(10L);
        response.setName("Winter Sale");
        response.setDiscountPercent(30.0);

        assertEquals(10L, response.getId());
        assertEquals("Winter Sale", response.getName());
        assertEquals(30.0, response.getDiscountPercent());
    }
}
