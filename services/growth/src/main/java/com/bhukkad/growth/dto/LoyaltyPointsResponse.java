package com.bhukkad.growth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyPointsResponse {
    private Long customerId;
    private Integer currentPoints;
    private Integer lifetimePoints;
    private Integer tierLevel;
    private String tierName;
    private Integer pointsToNextTier;
}
