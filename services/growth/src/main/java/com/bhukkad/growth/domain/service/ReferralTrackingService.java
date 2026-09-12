package com.bhukkad.growth.domain.service;

import com.bhukkad.growth.api.dto.response.ReferralStatsResponse;

public interface ReferralTrackingService {

    ReferralStatsResponse getReferralStats(Long customerId);

    String generateReferralCode(Long customerId);

    boolean applyReferralReward(Long referrerId, Long referredUserId);
}
