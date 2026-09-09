package com.bhukkad.growth.service;

import com.bhukkad.growth.dto.ReferralStatsResponse;

public interface ReferralTrackingService {

    ReferralStatsResponse getReferralStats(Long customerId);

    String generateReferralCode(Long customerId);

    boolean applyReferralReward(Long referrerId, Long referredUserId);
}
