package com.bhukkad.referral;

import com.bhukkad.dto.request.AffiliateCodeRequest;
import com.bhukkad.dto.response.AffiliateCodeResponse;
import com.bhukkad.dto.response.AffiliateStatsResponse;
import com.bhukkad.entity.AffiliateCode;
import com.bhukkad.entity.AffiliateReferral;
import com.bhukkad.entity.Customer;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.AffiliateCodeRepository;
import com.bhukkad.repository.AffiliateReferralRepository;
import com.bhukkad.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Influencer/affiliate code registry and referral tracking (Affiliate/Referral Program).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AffiliateService {

    private final AffiliateCodeRepository affiliateCodeRepository;
    private final AffiliateReferralRepository affiliateReferralRepository;
    private final CustomerRepository customerRepository;

    public List<AffiliateCodeResponse> listAll() {
        return affiliateCodeRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public AffiliateCodeResponse create(AffiliateCodeRequest request) {
        String code = normalizeCode(request.getCode());
        if (affiliateCodeRepository.existsByCodeIgnoreCase(code)) {
            throw new BusinessException("Affiliate code already exists: " + code);
        }
        if (request.getRewardAmount() == null || request.getRewardAmount() < 0) {
            throw new BusinessException("Reward amount must be zero or positive");
        }
        AffiliateCode affiliateCode = new AffiliateCode();
        affiliateCode.setCode(code);
        affiliateCode.setName(request.getName().trim());
        affiliateCode.setChannel(request.getChannel());
        affiliateCode.setRewardAmount(request.getRewardAmount());
        affiliateCode.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        return toResponse(affiliateCodeRepository.save(affiliateCode));
    }

    @Transactional
    public AffiliateCodeResponse update(Long id, AffiliateCodeRequest request) {
        AffiliateCode affiliateCode = findOrThrow(id);
        if (StringUtils.hasText(request.getCode())) {
            String code = normalizeCode(request.getCode());
            if (!code.equalsIgnoreCase(affiliateCode.getCode())
                    && affiliateCodeRepository.existsByCodeIgnoreCase(code)) {
                throw new BusinessException("Affiliate code already exists: " + code);
            }
            affiliateCode.setCode(code);
        }
        if (request.getName() != null) affiliateCode.setName(request.getName().trim());
        if (request.getChannel() != null) affiliateCode.setChannel(request.getChannel());
        if (request.getRewardAmount() != null) {
            if (request.getRewardAmount() < 0) {
                throw new BusinessException("Reward amount must be zero or positive");
            }
            affiliateCode.setRewardAmount(request.getRewardAmount());
        }
        if (request.getIsActive() != null) affiliateCode.setIsActive(request.getIsActive());
        return toResponse(affiliateCodeRepository.save(affiliateCode));
    }

    @Transactional
    public void deactivate(Long id) {
        AffiliateCode affiliateCode = findOrThrow(id);
        affiliateCode.setIsActive(false);
        affiliateCodeRepository.save(affiliateCode);
    }

    /**
     * Records a customer signup attributed to an affiliate code.
     *
     * @throws BusinessException when the code is unknown or disabled
     */
    @Transactional
    public void recordSignup(String code, Long customerId) {
        if (!StringUtils.hasText(code)) {
            return;
        }
        AffiliateCode affiliateCode = affiliateCodeRepository.findByCodeIgnoreCase(normalizeCode(code))
                .orElseThrow(() -> new BusinessException("Invalid affiliate code"));
        if (!Boolean.TRUE.equals(affiliateCode.getIsActive())) {
            throw new BusinessException("Affiliate code is not active");
        }
        if (affiliateReferralRepository.existsByAffiliateCodeIdAndCustomerId(affiliateCode.getId(), customerId)) {
            return;
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        AffiliateReferral referral = new AffiliateReferral();
        referral.setAffiliateCode(affiliateCode);
        referral.setCustomer(customer);
        referral.setRewardAmount(affiliateCode.getRewardAmount());
        affiliateReferralRepository.save(referral);
    }

    public AffiliateStatsResponse getStats(Long id) {
        AffiliateCode affiliateCode = findOrThrow(id);
        long total = affiliateReferralRepository.countByAffiliateCodeId(id);
        long paid = affiliateReferralRepository.countByAffiliateCodeIdAndStatus(
                id, AffiliateReferral.AffiliateReferralStatus.PAID);
        double totalReward = affiliateReferralRepository.sumRewardByAffiliateCodeId(id);
        List<AffiliateStatsResponse.AffiliateReferralEntry> recent = affiliateReferralRepository
                .findByAffiliateCodeIdOrderByCreatedAtDesc(id)
                .stream()
                .limit(20)
                .map(r -> AffiliateStatsResponse.AffiliateReferralEntry.builder()
                        .id(r.getId())
                        .customerId(r.getCustomer().getId())
                        .customerEmail(r.getCustomer().getEmail())
                        .rewardAmount(r.getRewardAmount())
                        .status(r.getStatus().name())
                        .createdAt(r.getCreatedAt().toString())
                        .build())
                .toList();
        return AffiliateStatsResponse.builder()
                .affiliateCodeId(affiliateCode.getId())
                .code(affiliateCode.getCode())
                .name(affiliateCode.getName())
                .totalReferrals(total)
                .paidReferrals(paid)
                .totalReward(totalReward)
                .recentReferrals(recent)
                .build();
    }

    private AffiliateCode findOrThrow(Long id) {
        return affiliateCodeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Affiliate code not found"));
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private AffiliateCodeResponse toResponse(AffiliateCode affiliateCode) {
        return AffiliateCodeResponse.builder()
                .id(affiliateCode.getId())
                .code(affiliateCode.getCode())
                .name(affiliateCode.getName())
                .channel(affiliateCode.getChannel())
                .rewardAmount(affiliateCode.getRewardAmount())
                .isActive(affiliateCode.getIsActive())
                .createdAt(affiliateCode.getCreatedAt() != null ? affiliateCode.getCreatedAt().toString() : null)
                .build();
    }
}
