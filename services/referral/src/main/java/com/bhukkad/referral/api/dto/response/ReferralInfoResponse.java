package com.bhukkad.referral.api.dto.response;

/** Referral rewards summary for a customer. */
public record ReferralInfoResponse(
        String referralCode,
        int referralsCount,
        double referralBonusEarned
) {}