package com.bhukkad.growth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralStatsResponse {
    private Long customerId;
    private Integer totalReferrals;
    private Integer successfulReferrals;
    private Integer pendingReferrals;
    private Integer referrerRewardEarned;
    private Integer referredUserRewardEarned;
}
