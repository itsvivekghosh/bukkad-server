package com.bhukkad.referral.domain.repository;

import com.bhukkad.referral.domain.entity.UserReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserReferralCodeRepository extends JpaRepository<UserReferralCode, Long> {

    Optional<UserReferralCode> findByReferralCode(String referralCode);

    Optional<UserReferralCode> findByCustomerId(Long customerId);

    @Query("SELECT COUNT(r) FROM UserReferralCode r WHERE r.referredBy = :customerId")
    long countByReferredBy(@Param("customerId") Long customerId);

    /**
     * Atomic row creation with a collision-checked code (ADR-005): the
     * {@code WHERE NOT EXISTS} makes the insert a no-op (0 rows) when the
     * code is taken, instead of a constraint violation that would poison the
     * transaction. {@code uk_referral_code} remains the hard backstop.
     * {@code ON CONFLICT (customer_id) DO NOTHING} makes a concurrent first
     * apply for the same customer a benign 0-row insert as well
     * (uq_user_referral_codes_customer_id, V11): the caller re-reads the
     * winner's row instead of erroring.
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_referral_codes
                (customer_id, referral_code, referred_by, referrals_count, referral_bonus_earned, created_at)
            SELECT :customerId, :code, :referredBy, 0, 0, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (SELECT 1 FROM user_referral_codes WHERE referral_code = :code)
            ON CONFLICT (customer_id) DO NOTHING
            """, nativeQuery = true)
    int insertReferralRow(@Param("customerId") Long customerId, @Param("code") String code,
                          @Param("referredBy") Long referredBy);

    /**
     * Conditional single-statement code assignment: 0 rows means the code is
     * already taken by another customer (draw a new one).
     */
    @Modifying
    @Query(value = """
            UPDATE user_referral_codes
            SET referral_code = :code, updated_at = CURRENT_TIMESTAMP
            WHERE customer_id = :customerId
              AND NOT EXISTS (SELECT 1 FROM user_referral_codes
                              WHERE referral_code = :code AND customer_id <> :customerId)
            """, nativeQuery = true)
    int assignCode(@Param("customerId") Long customerId, @Param("code") String code);

    /**
     * Atomic referrer-counter bump — never a read-modify-write, so concurrent
     * applies to the same referrer cannot lose an increment.
     */
    @Modifying
    @Query(value = """
            UPDATE user_referral_codes
            SET referrals_count = referrals_count + 1, updated_at = CURRENT_TIMESTAMP
            WHERE customer_id = :customerId
            """, nativeQuery = true)
    int incrementReferralsCount(@Param("customerId") Long customerId);

    /**
     * Conditional single-statement binding (ADR-005): 0 rows means the
     * customer already carries {@code referred_by} (re-apply rejected). The
     * V10 partial unique index {@code uq_user_referral_codes_referred_by} is
     * the hard backstop against any residual race.
     */
    @Modifying
    @Query(value = """
            UPDATE user_referral_codes
            SET referred_by = :referrerId, updated_at = CURRENT_TIMESTAMP
            WHERE customer_id = :customerId AND referred_by IS NULL
            """, nativeQuery = true)
    int bindReferrer(@Param("customerId") Long customerId, @Param("referrerId") Long referrerId);
}
