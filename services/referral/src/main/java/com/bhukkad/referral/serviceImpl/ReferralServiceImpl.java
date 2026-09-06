package com.bhukkad.referral.serviceImpl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.referral.config.ReferralServiceProperties;
import com.bhukkad.referral.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.entity.UserReferralCode;
import com.bhukkad.referral.repository.UserReferralCodeRepository;
import com.bhukkad.referral.service.ReferralService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Personal referral codes and rewards summaries.
 *
 * <p>Bonus accounting: the {@code referralBonusEarned} counter is incremented
 * here by the wallet domain's REFERRAL_BONUS events (or credited inline for
 * signups) — this service owns the referral state, not the money movement.</p>
 */
@Service
public class ReferralServiceImpl implements ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralServiceImpl.class);

    private final UserReferralCodeRepository referralCodeRepository;
    private final ReferralServiceProperties properties;

    public ReferralServiceImpl(UserReferralCodeRepository referralCodeRepository,
                               ReferralServiceProperties properties) {
        this.referralCodeRepository = referralCodeRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public ReferralInfoResponse getReferralInfo(Long customerId) {
        if (customerId == null) {
            throw new BusinessException("Customer id is required");
        }
        UserReferralCode referral = referralCodeRepository.findByCustomerId(customerId)
                .orElseGet(() -> createFor(customerId));
        if (!StringUtils.hasText(referral.getReferralCode())) {
            throw new BusinessException("Referral code not assigned");
        }
        long referralsCount = referralCodeRepository.countByReferredBy(customerId);
        return new ReferralInfoResponse(
                referral.getReferralCode(),
                (int) referralsCount,
                referral.getReferralBonusEarned());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isValidReferralCode(String code) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return referralCodeRepository.findByReferralCode(normalize(code)).isPresent();
    }

    @Override
    @Transactional
    public String generateAndSaveReferralCode(Long customerId) {
        if (customerId == null) {
            throw new BusinessException("Customer id is required");
        }
        UserReferralCode referral = referralCodeRepository.findByCustomerId(customerId)
                .orElseGet(() -> createFor(customerId));
        if (!StringUtils.hasText(referral.getReferralCode())) {
            referral.setReferralCode(generateUniqueCode(customerId));
            referral.setUpdatedAt(LocalDateTime.now());
            referralCodeRepository.save(referral);
        }
        return referral.getReferralCode();
    }

    @Override
    @Transactional
    public void applyReferral(Long newCustomerId, String customerEmail, String referralCodeInput) {
        if (newCustomerId == null || !StringUtils.hasText(referralCodeInput)) {
            return;
        }
        UserReferralCode newCustomerReferral = referralCodeRepository.findByCustomerId(newCustomerId)
                .orElseGet(() -> createFor(newCustomerId));
        UserReferralCode referrer = referralCodeRepository
                .findByReferralCode(normalize(referralCodeInput))
                .orElseThrow(() -> new BusinessException("Invalid referral code"));
        if (referrer.getCustomerId().equals(newCustomerId)) {
            throw new BusinessException("Cannot use your own referral code");
        }
        newCustomerReferral.setReferredBy(referrer.getCustomerId());
        newCustomerReferral.setUpdatedAt(LocalDateTime.now());
        referralCodeRepository.save(newCustomerReferral);

        referrer.setReferralsCount(referrer.getReferralsCount() + 1);
        referrer.setUpdatedAt(LocalDateTime.now());
        referralCodeRepository.save(referrer);
        log.info("Referral applied | customerId={} | referrerCustomerId={}",
                newCustomerId, referrer.getCustomerId());
    }

    private UserReferralCode createFor(Long customerId) {
        UserReferralCode referral = new UserReferralCode();
        referral.setCustomerId(customerId);
        referral.setReferralCode(generateUniqueCode(customerId));
        referral.setReferralsCount(0);
        referral.setReferralBonusEarned(0.0);
        referral.setCreatedAt(LocalDateTime.now());
        referral.setUpdatedAt(LocalDateTime.now());
        return referralCodeRepository.save(referral);
    }

    private String generateUniqueCode(Long customerId) {
        for (int attempt = 0; attempt < properties.getCodeGenerationAttempts(); attempt++) {
            String code = "BK" + customerId
                    + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
            if (referralCodeRepository.findByReferralCode(code).isEmpty()) {
                return code;
            }
        }
        return "BK" + customerId + System.currentTimeMillis() % 10000;
    }

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}