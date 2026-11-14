package com.bhukkad.engagement.growth.domain.service.impl;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.engagement.growth.infrastructure.client.ReferralServiceClient;
import com.bhukkad.engagement.growth.config.GrowthProperties;
import com.bhukkad.engagement.growth.api.dto.response.ReferralStatsResponse;
import com.bhukkad.engagement.growth.domain.entity.LoyaltyPointsLedger;
import com.bhukkad.engagement.growth.domain.entity.ReferralRecord;
import com.bhukkad.engagement.growth.domain.repository.LoyaltyPointBalanceRepository;
import com.bhukkad.engagement.growth.domain.repository.LoyaltyPointsLedgerRepository;
import com.bhukkad.engagement.growth.domain.repository.ReferralRecordRepository;
import com.bhukkad.engagement.growth.domain.service.ReferralTrackingService;
import com.bhukkad.common.error.UpstreamUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable referral tracking (ADR-005 / audit feature #4). Replaces the
 * Redis-hash reward counters, which were both farmable and lossy.
 * 
 * <p>Optimizations:
 * <ul>
 *   <li>Async reward processing with circuit breaker for resilience</li>
 *   <li>Caffeine cache for referral stats (5min TTL)</li>
 *   <li>Retry logic for transient failures</li>
 *   <li>Async code generation fallback</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableAsync
public class ReferralTrackingServiceImpl implements ReferralTrackingService {

    private final ReferralRecordRepository referralRecordRepository;
    private final LoyaltyPointsLedgerRepository ledgerRepository;
    private final LoyaltyPointBalanceRepository balanceRepository;
    private final ReferralServiceClient referralServiceClient;
    private final GrowthProperties growthProperties;

    // Caffeine cache for referral stats - 5min TTL, bounded to 5000 entries
    private final Cache<Long, ReferralStatsResponse> referralStatsCache = Caffeine.newBuilder()
        .maximumSize(5_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

    @Override
    @Transactional(readOnly = true)
    public ReferralStatsResponse getReferralStats(Long customerId) {
        return referralStatsCache.get(customerId, key -> {
            long successful = referralRecordRepository.countByReferrerId(customerId);
            int referrerReward = growthProperties.getReferral().getReferrerReward();
            return ReferralStatsResponse.builder()
                    .customerId(customerId)
                    .totalReferrals((int) successful)
                    .successfulReferrals((int) successful)
                    .pendingReferrals(0)
                    .referrerRewardEarned((int) successful * referrerReward)
                    .referredUserRewardEarned(growthProperties.getReferral().getReferredUserReward())
                    .build();
        });
    }

    @Override
    @CircuitBreaker(name = "referralCodeGenerator", fallbackMethod = "generateReferralCodeFallback")
    @Retry(name = "referralCodeGenerator")
    public String generateReferralCode(Long customerId) {
        // ADR-005: the referral module is the single code generator.
        String delegated = referralServiceClient.generateCode(customerId);
        if (delegated == null) {
            throw new UpstreamUnavailableException("referral", null);
        }
        return delegated;
    }

    /**
     * Fallback when referral code generator circuit breaker is open.
     * Generates a local deterministic code.
     */
    public String generateReferralCodeFallback(Long customerId, Throwable throwable) {
        log.warn("REFERRAL_CODE_GENERATOR_CIRCUIT_OPEN | customerId={} | error={}", 
                customerId, throwable.getMessage());
        // Fallback: generate a deterministic local code
        return "BKR" + customerId + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 6).toUpperCase(Locale.ROOT);
    }

    @Transactional
    @Async
    public CompletableFuture<Boolean> applyReferralRewardAsync(Long referrerId, Long referredUserId) {
        try {
            boolean result = applyReferralReward(referrerId, referredUserId);
            return CompletableFuture.completedFuture(result);
        } catch (Exception ex) {
            log.error("REFERRAL_APPLY_ASYNC_FAILED | referrer={} referred={} error={}", 
                    referrerId, referredUserId, ex.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Idempotent apply: a customer with an existing referral record is never
     * re-bound, and the referrer reward is credited exactly once.
     */
    @Override
    @Transactional
    public boolean applyReferralReward(Long referrerId, Long referredUserId) {
        if (referralRecordRepository.existsByReferredCustomerId(referredUserId)) {
            log.info("Referral apply rejected: customer {} already has an active referral", referredUserId);
            invalidateReferralStatsCache(referrerId);
            return false;
        }

        ReferralRecord record = new ReferralRecord();
        record.setReferrerId(referrerId);
        record.setReferredCustomerId(referredUserId);
        record.setReferralCode(trackingCode(referrerId));
        record.setStatus(ReferralRecord.STATUS_COMPLETED);
        record.setRewardCredited(false);
        try {
            referralRecordRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate apply lost the V10 partial-unique race.
            log.info("Referral apply lost the unique race for customer {}", referredUserId);
            invalidateReferralStatsCache(referrerId);
            return false;
        }

        int reward = growthProperties.getReferral().getReferrerReward();
        if (reward > 0) {
            // Ledger row + atomic upsert (LoyaltyService contract) — the
            // reward is real loyalty points, credited exactly once: the
            // tracking row, the ledger row and the flag share this commit.
            ledgerRepository.save(LoyaltyPointsLedger.credit(referrerId, reward,
                    "REFERRAL_REWARD", "referral:" + record.getId()));
            balanceRepository.credit(referrerId, reward);
        }
        record.setRewardCredited(true);
        record.setCompletedAt(java.time.LocalDateTime.now());
        referralRecordRepository.save(record);
        
        // Invalidate stats cache after successful apply
        invalidateReferralStatsCache(referrerId);
        
        log.info("Referral applied | referrer={} referred={} reward={}", referrerId, referredUserId, reward);
        return true;
    }

    /**
     * Invalidates referral stats cache for a given referrer.
     */
    public void invalidateReferralStatsCache(Long referrerId) {
        referralStatsCache.invalidate(referrerId);
        log.debug("REFERRAL_STATS_CACHE_INVALIDATED | referrerId={}", referrerId);
    }

    /** Non-null marker for the tracking table's {@code referral_code} column. */
    private String trackingCode(Long referrerId) {
        return "BKR" + referrerId + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 6).toUpperCase(Locale.ROOT);
    }
}
