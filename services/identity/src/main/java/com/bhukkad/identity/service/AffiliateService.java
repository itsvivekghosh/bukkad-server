package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.identity.domain.AffiliateCode;
import com.bhukkad.identity.domain.AffiliateCodeRepository;
import com.bhukkad.identity.domain.AffiliateReferral;
import com.bhukkad.identity.domain.AffiliateReferralRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Affiliate/referral tracking (Priority 1).
 */
@Service
@RequiredArgsConstructor
public class AffiliateService {

    private final AffiliateCodeRepository codeRepository;
    private final AffiliateReferralRepository referralRepository;

    @Transactional
    public AffiliateCode createCode(Long restaurantId, String code, BigDecimal commissionPct) {
        if (codeRepository.findByCode(code).isPresent()) {
            throw new BusinessException("Affiliate code already exists: " + code);
        }
        AffiliateCode affiliateCode = new AffiliateCode();
        affiliateCode.setRestaurantId(restaurantId);
        affiliateCode.setCode(code);
        affiliateCode.setCommissionPct(commissionPct != null ? commissionPct : new BigDecimal("10.00"));
        return codeRepository.save(affiliateCode);
    }

    @Transactional
    public AffiliateReferral trackClick(String code, Long referredBy) {
        if (codeRepository.findByCode(code).isEmpty()) {
            throw new BusinessException("Unknown affiliate code: " + code);
        }
        AffiliateReferral referral = new AffiliateReferral();
        referral.setAffiliateCode(code);
        referral.setReferredBy(referredBy);
        referral.setStatus("CLICKED");
        return referralRepository.save(referral);
    }
}