package com.bhukkad.referral.repository;

import com.bhukkad.referral.entity.UserReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;
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
}