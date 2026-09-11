package com.bhukkad.referral.repository;

import com.bhukkad.referral.entity.ReferralRewardLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReferralRewardLedgerRepository extends JpaRepository<ReferralRewardLedger, Long> {

    Optional<ReferralRewardLedger> findByEventTypeAndEventId(String eventType, String eventId);

    Optional<ReferralRewardLedger> findByReferredCustomerIdAndRewardType(Long referredCustomerId, String rewardType);

    /**
     * Exactly-once reward credit (ADR-005): a single conditional insert
     * guarded by BOTH uniqueness dimensions — the replayed event id (e.g.
     * {@code order:<orderId>}) and the one-reward-per-referee cap. Returns
     * 1 when the ledger row was appended, 0 when it already existed (never a
     * constraint violation, so a concurrent duplicate cannot poison the tx).
     * {@code ON CONFLICT DO NOTHING} closes the check-then-insert race: two
     * workers can both pass the NOT EXISTS probes before either commits, and
     * the unique constraints (uq_referral_rewards_event,
     * uq_referral_rewards_per_referee) turn the loser into a 0-row insert
     * instead of an aborted transaction.
     */
    @Modifying
    @Query(value = """
            INSERT INTO referral_rewards_ledger
                (receiver_customer_id, referred_customer_id, reward_type, reward_amount,
                 event_type, event_id, order_id, created_at)
            SELECT :receiverId, :referredId, :rewardType, :amount, :eventType, :eventId, :orderId, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (SELECT 1 FROM referral_rewards_ledger
                              WHERE event_type = :eventType AND event_id = :eventId)
              AND NOT EXISTS (SELECT 1 FROM referral_rewards_ledger
                              WHERE referred_customer_id = :referredId AND reward_type = :rewardType)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertRewardIfAbsent(@Param("receiverId") Long receiverId, @Param("referredId") Long referredId,
                             @Param("rewardType") String rewardType, @Param("amount") double amount,
                             @Param("eventType") String eventType, @Param("eventId") String eventId,
                             @Param("orderId") Long orderId);

    /** Atomic summary bump: no read-modify-write, so concurrent rewards never lose an increment. */
    @Modifying
    @Query(value = """
            UPDATE user_referral_codes
            SET referral_bonus_earned = referral_bonus_earned + :amount, updated_at = CURRENT_TIMESTAMP
            WHERE customer_id = :customerId
            """, nativeQuery = true)
    int addBonusEarned(@Param("customerId") Long customerId, @Param("amount") double amount);
}

