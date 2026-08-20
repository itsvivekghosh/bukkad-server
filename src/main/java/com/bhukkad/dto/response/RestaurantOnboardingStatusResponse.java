package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Onboarding application status for the restaurants owned by one owner. */
@Data
@Builder
public class RestaurantOnboardingStatusResponse {
    private List<RestaurantOnboardingItem> restaurants;

    @Data
    @Builder
    public static class RestaurantOnboardingItem {
        private Long restaurantId;
        private String name;
        private String onboardingStatus;
        private String rejectionReason;
        private Boolean isActive;
    }
}
