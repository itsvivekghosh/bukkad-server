package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Referral tracking stats for one affiliate code. */
@Data
@Builder
public class AffiliateStatsResponse {
    private Long affiliateCodeId;
    private String code;
    private String name;
    private long totalReferrals;
    private long paidReferrals;
    private double totalReward;
    private List<AffiliateReferralEntry> recentReferrals;

    @Data
    @Builder
    public static class AffiliateReferralEntry {
        private Long id;
        private Long customerId;
        private String customerEmail;
        private double rewardAmount;
        private String status;
        private String createdAt;
    }
}
