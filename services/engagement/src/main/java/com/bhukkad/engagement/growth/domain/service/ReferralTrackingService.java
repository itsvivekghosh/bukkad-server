package com.bhukkad.engagement.growth.domain.service;

import com.bhukkad.engagement.growth.api.dto.response.ReferralStatsResponse;

public interface ReferralTrackingService {

    ReferralStatsResponse getReferralStats(Long customerId);

    String generateReferralCode(Long customerId);

    boolean applyReferralReward(Long referrerId, Long referredUserId);
}
