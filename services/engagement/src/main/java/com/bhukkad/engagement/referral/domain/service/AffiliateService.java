package com.bhukkad.engagement.referral.domain.service;

import com.bhukkad.engagement.referral.api.dto.request.AffiliateCodeRequest;
import com.bhukkad.engagement.referral.api.dto.response.AffiliateCodeResponse;
import com.bhukkad.engagement.referral.api.dto.response.AffiliateStatsResponse;

import java.util.List;

/**
 * Influencer/affiliate code registry and referral tracking.
 */
public interface AffiliateService {

    List<AffiliateCodeResponse> listAll();

    AffiliateCodeResponse create(AffiliateCodeRequest request);

    AffiliateCodeResponse update(Long id, AffiliateCodeRequest request);

    void deactivate(Long id);

    /**
     * Records a customer signup attributed to an affiliate code.
     *
     * @throws com.bhukkad.common.error.BusinessException when the code is unknown or disabled
     */
    void recordSignup(String code, Long customerId, String customerEmail);

    AffiliateStatsResponse getStats(Long id);
}