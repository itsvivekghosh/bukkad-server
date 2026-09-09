package com.bhukkad.referral.dto.response;

import java.util.List;

/** Aggregate signup stats for one affiliate code. */
public record AffiliateStatsResponse(
        Long affiliateCodeId,
        String code,
        String name,
        long totalReferrals,
        long paidReferrals,
        double totalReward,
        List<AffiliateReferralEntry> recentReferrals
) {
    /** One attributed signup entry. */
    public record AffiliateReferralEntry(
            Long id,
            Long customerId,
            String customerEmail,
            double rewardAmount,
            String status,
            String createdAt
    ) {}
}