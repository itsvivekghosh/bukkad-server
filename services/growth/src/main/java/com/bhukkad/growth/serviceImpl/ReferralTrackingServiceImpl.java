package com.bhukkad.growth.serviceImpl;

import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.dto.ReferralStatsResponse;
import com.bhukkad.growth.service.ReferralTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralTrackingServiceImpl implements ReferralTrackingService {

    private static final String REFERRAL_CODE_KEY_PREFIX = "referral:code:";
    private static final String REFERRAL_STATS_KEY_PREFIX = "referral:stats:";

    private final StringRedisTemplate redisTemplate;
    private final GrowthProperties growthProperties;

    @Override
    public ReferralStatsResponse getReferralStats(Long customerId) {
        String statsKey = REFERRAL_STATS_KEY_PREFIX + customerId;

        Integer totalReferrals = getIntValue(statsKey, "total");
        Integer successfulReferrals = getIntValue(statsKey, "successful");
        Integer pendingReferrals = getIntValue(statsKey, "pending");
        Integer referrerReward = getIntValue(statsKey, "referrer_reward");
        Integer referredReward = getIntValue(statsKey, "referred_reward");

        return ReferralStatsResponse.builder()
                .customerId(customerId)
                .totalReferrals(totalReferrals)
                .successfulReferrals(successfulReferrals)
                .pendingReferrals(pendingReferrals)
                .referrerRewardEarned(referrerReward)
                .referredUserRewardEarned(referredReward)
                .build();
    }

    @Override
    public String generateReferralCode(Long customerId) {
        String codeKey = REFERRAL_CODE_KEY_PREFIX + customerId;
        String existingCode = redisTemplate.opsForValue().get(codeKey);

        if (existingCode != null) {
            return existingCode;
        }

        String newCode = generateUniqueCode();
        redisTemplate.opsForValue().set(codeKey, newCode);
        redisTemplate.opsForValue().set("referral:customer:" + newCode, customerId.toString());

        log.info("Generated referral code {} for customer {}", newCode, customerId);
        return newCode;
    }

    @Override
    public boolean applyReferralReward(Long referrerId, Long referredUserId) {
        int maxReferrals = growthProperties.getReferral().getMaxReferralsPerUser();
        String statsKey = REFERRAL_STATS_KEY_PREFIX + referrerId;

        Integer currentTotal = getIntValue(statsKey, "total");
        if (currentTotal != null && currentTotal >= maxReferrals) {
            log.warn("Customer {} has reached max referrals limit of {}", referrerId, maxReferrals);
            return false;
        }

        // Credit referrer
        int referrerReward = growthProperties.getReferral().getReferrerReward();
        redisTemplate.opsForHash().increment(statsKey, "referrer_reward", referrerReward);
        redisTemplate.opsForHash().increment(statsKey, "total", 1);
        redisTemplate.opsForHash().increment(statsKey, "successful", 1);

        // Credit referred user
        String referredStatsKey = REFERRAL_STATS_KEY_PREFIX + referredUserId;
        int referredReward = growthProperties.getReferral().getReferredUserReward();
        redisTemplate.opsForHash().increment(referredStatsKey, "referred_reward", referredReward);
        redisTemplate.opsForHash().increment(referredStatsKey, "successful", 1);

        log.info("Applied referral rewards: referrer {} got {}, referred {} got {}",
                referrerId, referrerReward, referredUserId, referredReward);

        return true;
    }

    private String generateUniqueCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Integer getIntValue(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        if (value == null) {
            return 0;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            return ((Long) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
