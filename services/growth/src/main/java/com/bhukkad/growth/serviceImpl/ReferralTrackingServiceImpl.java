package com.bhukkad.growth.serviceImpl;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.growth.client.ReferralServiceClient;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.dto.ReferralStatsResponse;
import com.bhukkad.growth.entity.LoyaltyPointsLedger;
import com.bhukkad.growth.entity.ReferralRecord;
import com.bhukkad.growth.repository.LoyaltyPointBalanceRepository;
import com.bhukkad.growth.repository.LoyaltyPointsLedgerRepository;
import com.bhukkad.growth.repository.ReferralRecordRepository;
import com.bhukkad.growth.service.ReferralTrackingService;
import com.bhukkad.common.error.UpstreamUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Durable referral tracking (ADR-005 / audit feature #4). Replaces the
 * Redis-hash reward counters, which were both farmable and lossy.
 *
 * <p>Integrity rules enforced here:</p>
 * <ul>
 *   <li><b>One active referral per new customer</b> — early-return guard plus
 *       the V10 partial unique index {@code uq_referral_records_referred_customer};
 *       a race loser gets a constraint violation and the apply is reported as
 *       already-done, never double-rewarded.</li>
 *   <li><b>Reward credited exactly once</b> — the tracking row, the reward
 *       ledger row and the {@code reward_credited} flag commit in ONE
 *       transaction, so a crash can never leave a reward half-claimed.</li>
 *   <li><b>Single code generator</b> — codes are delegated to the referral
 *       module's internal API (ADR-005).</li>
 * </ul>
 */
@Slf4j
@Service
public class ReferralTrackingServiceImpl implements ReferralTrackingService {

    private final ReferralRecordRepository referralRecordRepository;
    private final LoyaltyPointsLedgerRepository ledgerRepository;
    private final LoyaltyPointBalanceRepository balanceRepository;
    private final ReferralServiceClient referralServiceClient;
    private final GrowthProperties growthProperties;

    public ReferralTrackingServiceImpl(ReferralRecordRepository referralRecordRepository,
                                       LoyaltyPointsLedgerRepository ledgerRepository,
                                       LoyaltyPointBalanceRepository balanceRepository,
                                       ReferralServiceClient referralServiceClient,
                                       GrowthProperties growthProperties) {
        this.referralRecordRepository = referralRecordRepository;
        this.ledgerRepository = ledgerRepository;
        this.balanceRepository = balanceRepository;
        this.referralServiceClient = referralServiceClient;
        this.growthProperties = growthProperties;
    }

    @Override
    @Transactional(readOnly = true)
    public ReferralStatsResponse getReferralStats(Long customerId) {
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
    }

    @Override
    @Transactional
    public String generateReferralCode(Long customerId) {
        // ADR-005: the referral module is the single code generator.
        String delegated = referralServiceClient.generateCode(customerId);
        if (delegated == null) {
            throw new UpstreamUnavailableException("referral", null);
        }
        return delegated;
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
        log.info("Referral applied | referrer={} referred={} reward={}", referrerId, referredUserId, reward);
        return true;
    }

    /** Non-null marker for the tracking table's {@code referral_code} column. */
    private String trackingCode(Long referrerId) {
        return "BKR" + referrerId + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 6).toUpperCase(Locale.ROOT);
    }
}
