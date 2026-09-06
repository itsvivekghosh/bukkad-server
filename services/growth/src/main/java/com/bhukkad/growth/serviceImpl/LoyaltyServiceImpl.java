package com.bhukkad.growth.serviceImpl;

import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.dto.LoyaltyPointsResponse;
import com.bhukkad.growth.service.LoyaltyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyServiceImpl implements LoyaltyService {

    private static final String LOYALTY_KEY_PREFIX = "loyalty:points:";
    private static final String LIFETIME_KEY_PREFIX = "loyalty:lifetime:";
    private static final String TIER_KEY_PREFIX = "loyalty:tier:";

    private final StringRedisTemplate redisTemplate;
    private final GrowthProperties growthProperties;

    @Override
    public LoyaltyPointsResponse getLoyaltyPoints(Long customerId) {
        int currentPoints = getCurrentPoints(customerId);
        int lifetimePoints = getLifetimePoints(customerId);
        int tierLevel = calculateTierLevel(lifetimePoints);
        String tierName = getTierName(tierLevel);
        int pointsToNextTier = getPointsToNextTier(lifetimePoints, tierLevel);

        return LoyaltyPointsResponse.builder()
                .customerId(customerId)
                .currentPoints(currentPoints)
                .lifetimePoints(lifetimePoints)
                .tierLevel(tierLevel)
                .tierName(tierName)
                .pointsToNextTier(pointsToNextTier)
                .build();
    }

    @Override
    public void creditPoints(Long customerId, int points, String reason) {
        if (points <= 0) {
            log.warn("Invalid points credit attempt: {} for customer {}", points, customerId);
            return;
        }

        String pointsKey = LOYALTY_KEY_PREFIX + customerId;
        String lifetimeKey = LIFETIME_KEY_PREFIX + customerId;

        redisTemplate.opsForValue().increment(pointsKey, points);
        redisTemplate.opsForValue().increment(lifetimeKey, points);

        log.info("Credited {} points to customer {} for: {}", points, customerId, reason);
    }

    @Override
    public boolean redeemPoints(Long customerId, int points) {
        if (points <= 0) {
            return false;
        }

        int currentPoints = getCurrentPoints(customerId);
        if (currentPoints < points) {
            log.warn("Insufficient points for redemption: requested {} but only {} available for customer {}",
                    points, currentPoints, customerId);
            return false;
        }

        int minRedemption = growthProperties.getLoyalty().getMinRedemptionPoints();
        if (points < minRedemption) {
            log.warn("Points {} below minimum redemption threshold {}", points, minRedemption);
            return false;
        }

        String pointsKey = LOYALTY_KEY_PREFIX + customerId;
        redisTemplate.opsForValue().decrement(pointsKey, points);

        log.info("Redeemed {} points for customer {}", points, customerId);
        return true;
    }

    @Override
    public int calculatePointsForOrder(double orderAmount) {
        int pointsPerRupee = growthProperties.getLoyalty().getPointsPerRupee();
        return BigDecimal.valueOf(orderAmount)
                .multiply(BigDecimal.valueOf(pointsPerRupee))
                .setScale(0, RoundingMode.FLOOR)
                .intValue();
    }

    private int getCurrentPoints(Long customerId) {
        String key = LOYALTY_KEY_PREFIX + customerId;
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Integer.parseInt(value) : 0;
    }

    private int getLifetimePoints(Long customerId) {
        String key = LIFETIME_KEY_PREFIX + customerId;
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Integer.parseInt(value) : 0;
    }

    private int calculateTierLevel(int lifetimePoints) {
        if (lifetimePoints >= 10000) return 5; // Platinum
        if (lifetimePoints >= 5000) return 4;  // Gold
        if (lifetimePoints >= 2000) return 3;   // Silver
        if (lifetimePoints >= 500) return 2;     // Bronze
        return 1; // Basic
    }

    private String getTierName(int tierLevel) {
        return switch (tierLevel) {
            case 5 -> "Platinum";
            case 4 -> "Gold";
            case 3 -> "Silver";
            case 2 -> "Bronze";
            default -> "Basic";
        };
    }

    private int getPointsToNextTier(int lifetimePoints, int currentTier) {
        int[] tierThresholds = {0, 500, 2000, 5000, 10000};
        if (currentTier >= 5) return 0;

        int nextThreshold = tierThresholds[currentTier];
        return Math.max(0, nextThreshold - lifetimePoints);
    }
}
