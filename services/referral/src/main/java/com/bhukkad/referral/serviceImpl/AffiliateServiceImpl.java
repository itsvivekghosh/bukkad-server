package com.bhukkad.referral.serviceImpl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.referral.dto.request.AffiliateCodeRequest;
import com.bhukkad.referral.dto.response.AffiliateCodeResponse;
import com.bhukkad.referral.dto.response.AffiliateStatsResponse;
import com.bhukkad.referral.entity.AffiliateCode;
import com.bhukkad.referral.entity.AffiliateReferral;
import com.bhukkad.referral.entity.AffiliateReferral.AffiliateReferralStatus;
import com.bhukkad.referral.repository.AffiliateCodeRepository;
import com.bhukkad.referral.repository.AffiliateReferralRepository;
import com.bhukkad.referral.service.AffiliateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Influencer/affiliate code registry and referral tracking.
 */
@Service
public class AffiliateServiceImpl implements AffiliateService {

    private final AffiliateCodeRepository affiliateCodeRepository;
    private final AffiliateReferralRepository affiliateReferralRepository;

    public AffiliateServiceImpl(AffiliateCodeRepository affiliateCodeRepository,
                                AffiliateReferralRepository affiliateReferralRepository) {
        this.affiliateCodeRepository = affiliateCodeRepository;
        this.affiliateReferralRepository = affiliateReferralRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AffiliateCodeResponse> listAll() {
        return affiliateCodeRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Override
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
        affiliateCode.setCreatedAt(LocalDateTime.now());
        return toResponse(affiliateCodeRepository.save(affiliateCode));
    }

    @Override
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

    @Override
    @Transactional
    public void deactivate(Long id) {
        AffiliateCode affiliateCode = findOrThrow(id);
        affiliateCode.setIsActive(false);
        affiliateCodeRepository.save(affiliateCode);
    }

    @Override
    @Transactional
    public void recordSignup(String code, Long customerId, String customerEmail) {
        if (!StringUtils.hasText(code) || customerId == null) {
            return;
        }
        AffiliateCode affiliateCode = affiliateCodeRepository.findByCodeIgnoreCase(normalizeCode(code))
                .orElseThrow(() -> new BusinessException("Invalid affiliate code"));
        if (!Boolean.TRUE.equals(affiliateCode.getIsActive())) {
            throw new BusinessException("Affiliate code is not active");
        }
        if (affiliateReferralRepository.existsByAffiliateCodeIdAndCustomerId(
                affiliateCode.getId(), customerId)) {
            return;
        }
        AffiliateReferral referral = new AffiliateReferral();
        referral.setAffiliateCode(affiliateCode);
        referral.setCustomerId(customerId);
        referral.setCustomerEmail(customerEmail);
        referral.setRewardAmount(affiliateCode.getRewardAmount());
        referral.setStatus(AffiliateReferralStatus.PENDING);
        referral.setCreatedAt(LocalDateTime.now());
        affiliateReferralRepository.save(referral);
    }

    @Override
    @Transactional(readOnly = true)
    public AffiliateStatsResponse getStats(Long id) {
        AffiliateCode affiliateCode = findOrThrow(id);
        long total = affiliateReferralRepository.countByAffiliateCodeId(id);
        long paid = affiliateReferralRepository.countByAffiliateCodeIdAndStatus(
                id, AffiliateReferralStatus.PAID);
        double totalReward = affiliateReferralRepository.sumRewardByAffiliateCodeId(id);
        List<AffiliateStatsResponse.AffiliateReferralEntry> recent = affiliateReferralRepository
                .findTop20ByAffiliateCodeIdOrderByCreatedAtDesc(id)
                .stream()
                .map(r -> new AffiliateStatsResponse.AffiliateReferralEntry(
                        r.getId(),
                        r.getCustomerId(),
                        r.getCustomerEmail(),
                        r.getRewardAmount(),
                        r.getStatus().name(),
                        r.getCreatedAt().toString()))
                .toList();
        return new AffiliateStatsResponse(
                affiliateCode.getId(),
                affiliateCode.getCode(),
                affiliateCode.getName(),
                total,
                paid,
                totalReward,
                recent);
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
                .createdAt(affiliateCode.getCreatedAt() != null
                        ? affiliateCode.getCreatedAt().toString() : null)
                .build();
    }
}