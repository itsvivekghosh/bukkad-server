package com.bhukkad.growth.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.api.dto.response.LoyaltyPointsResponse;
import com.bhukkad.growth.domain.entity.LoyaltyPointBalance;
import com.bhukkad.growth.domain.entity.LoyaltyPointsLedger;
import com.bhukkad.growth.domain.repository.LoyaltyPointBalanceRepository;
import com.bhukkad.growth.domain.repository.LoyaltyPointsLedgerRepository;
import com.bhukkad.growth.domain.service.LoyaltyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Durable-ledger loyalty service (ADR-005 / audit feature #4).
 *
 * <p>The append-only {@code loyalty_points_ledger} table is the source of
 * truth; {@code loyalty_point_balances} is an O(1) read model and the Redis
 * counter is a disposable cache (write-through on change, rebuilt from the
 * ledger on miss). A Redis flush can therefore no longer destroy balances.</p>
 *
 * <p>Concurrency contract:</p>
 * <ul>
 *   <li><b>Credit</b> — append the ledger row and apply the atomic
 *       single-statement upsert ({@code INSERT ... ON CONFLICT DO UPDATE
 *       points = points + :delta}) inside ONE transaction; never a Java
 *       read-modify-write.</li>
 *   <li><b>Redeem</b> — conditional single-statement decrement
 *       ({@code UPDATE ... SET points = points - :amt WHERE customer_id=:id
 *       AND points >= :amt}); 0 updated rows means insufficient balance. No
 *       check-then-act window, so N concurrent redemptions can never drive a
 *       balance negative and exactly the affordable count succeed.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyServiceImpl implements LoyaltyService {

    private static final String LOYALTY_KEY_PREFIX = "loyalty:points:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /** Single-credit ceiling; larger grants must go through an audited admin flow. */
    private static final int MAX_CREDIT_POINTS = 100_000;

    private final LoyaltyPointsLedgerRepository ledgerRepository;
    private final LoyaltyPointBalanceRepository balanceRepository;
    private final StringRedisTemplate redisTemplate;
    private final GrowthProperties growthProperties;

    @Override
    @Transactional(readOnly = true)
    public LoyaltyPointsResponse getLoyaltyPoints(Long customerId) {
        int currentPoints = currentPoints(customerId);
        int lifetimePoints = lifetimePoints(customerId);
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

    /**
     * One-transaction credit (ADR-005): ledger append + atomic balance upsert.
     * {@code referenceId} (the caller's idempotency key) makes a replay a
     * no-op instead of a second grant.
     */
    @Override
    @Transactional
    public void creditPoints(Long customerId, int points, String reason) {
        creditPoints(customerId, points, reason, null);
    }

    @Transactional
    public void creditPoints(Long customerId, int points, String reason, String referenceId) {
        if (points <= 0) {
            log.warn("Invalid points credit attempt: {} for customer {}", points, customerId);
            throw new BusinessException("Points to credit must be positive");
        }
        if (points > MAX_CREDIT_POINTS) {
            log.warn("Rejected oversized points credit: {} for customer {}", points, customerId);
            throw new BusinessException("Single credit exceeds the allowed ceiling");
        }
        if (referenceId != null && ledgerRepository.existsByReferenceId(referenceId)) {
            // Replay of an already-processed credit (idempotency backstop at
            // the ledger level under the caller's unique idempotency lock).
            log.info("Credit replay ignored customerId={} referenceId={}", customerId, referenceId);
            return;
        }

        ledgerRepository.save(LoyaltyPointsLedger.credit(customerId, points, reason, referenceId));
        balanceRepository.credit(customerId, points);

        writeThroughCache(customerId);
        log.info("Credited {} points to customer {} for: {}", points, customerId, reason);
    }

    @Override
    @Transactional
    public boolean redeemPoints(Long customerId, int points) {
        if (points <= 0) {
            return false;
        }

        int minRedemption = growthProperties.getLoyalty().getMinRedemptionPoints();
        if (points < minRedemption) {
            log.warn("Points {} below minimum redemption threshold {}", points, minRedemption);
            return false;
        }

        // The balance row may not exist yet (customer with ledger history but
        // no materialized row). Materialize, then conditionally decrement.
        balanceRepository.materializeFromLedger(customerId);
        int updated = balanceRepository.redeem(customerId, points);
        if (updated == 0) {
            log.warn("Insufficient points for redemption: requested {} for customer {}", points, customerId);
            return false;
        }

        ledgerRepository.save(LoyaltyPointsLedger.debit(customerId, points, "REDEMPTION", null));

        // Cache tracks the post-redeem balance; a stale value self-heals via
        // the rebuild-on-miss path and the nightly reconciliation.
        writeThroughCache(customerId);
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

    /**
     * Current points read path: Redis cache first (write-through warm);
     * on miss rebuild from the ledger sum and re-warm the cache.
     */
    private int currentPoints(Long customerId) {
        String key = LOYALTY_KEY_PREFIX + customerId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
            log.warn("Loyalty cache unavailable; reading ledger directly customerId={}", customerId);
        }
        long fromLedger = ledgerRepository.ledgerBalance(customerId);
        try {
            redisTemplate.opsForValue().set(key, Long.toString(fromLedger), CACHE_TTL);
        } catch (org.springframework.data.redis.RedisConnectionFailureException ignored) {
            // Cache stays cold; the ledger value just served is authoritative.
        }
        return Math.toIntExact(fromLedger);
    }

    private int lifetimePoints(Long customerId) {
        return balanceRepository.findByCustomerId(customerId)
                .map(LoyaltyPointBalance::lifetimePointsAsInt)
                .orElseGet(() -> Math.toIntExact(ledgerRepository.ledgerLifetime(customerId)));
    }

    private void writeThroughCache(Long customerId) {
        try {
            balanceRepository.findByCustomerId(customerId).ifPresent(balance ->
                    redisTemplate.opsForValue().set(
                            LOYALTY_KEY_PREFIX + customerId, Long.toString(balance.getPoints()), CACHE_TTL));
        } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
            // Write-through is best-effort: the DB row is authoritative and
            // the next read rebuilds the cache from the ledger.
            log.warn("Loyalty cache write-through skipped customerId={}", customerId);
        }
    }

    private int calculateTierLevel(int lifetimePoints) {
        if (lifetimePoints >= 10000) return 5; // Platinum
        if (lifetimePoints >= 5000) return 4;  // Gold
        if (lifetimePoints >= 2000) return 3;   // Silver
        if (lifetimePoints >= 500) return 2;   // Bronze
        return 1; // Member
    }

    private String getTierName(int tierLevel) {
        return switch (tierLevel) {
            case 5 -> "Platinum";
            case 4 -> "Gold";
            case 3 -> "Silver";
            case 2 -> "Bronze";
            default -> "Member";
        };
    }

    private int getPointsToNextTier(int lifetimePoints, int tierLevel) {
        int[] thresholds = {0, 500, 2000, 5000, 10000};
        if (tierLevel >= thresholds.length) {
            return 0;
        }
        return thresholds[tierLevel] - lifetimePoints;
    }
}
