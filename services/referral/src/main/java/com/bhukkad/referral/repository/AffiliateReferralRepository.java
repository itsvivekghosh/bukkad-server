package com.bhukkad.referral.repository;

import com.bhukkad.referral.entity.AffiliateReferral;
import com.bhukkad.referral.entity.AffiliateReferral.AffiliateReferralStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AffiliateReferralRepository extends JpaRepository<AffiliateReferral, Long> {

    boolean existsByAffiliateCodeIdAndCustomerId(Long affiliateCodeId, Long customerId);

    long countByAffiliateCodeId(Long affiliateCodeId);

    long countByAffiliateCodeIdAndStatus(Long affiliateCodeId, AffiliateReferralStatus status);

    @Query("SELECT COALESCE(SUM(r.rewardAmount), 0) FROM AffiliateReferral r " +
            "WHERE r.affiliateCode.id = :affiliateCodeId")
    double sumRewardByAffiliateCodeId(@Param("affiliateCodeId") Long affiliateCodeId);

    List<AffiliateReferral> findTop20ByAffiliateCodeIdOrderByCreatedAtDesc(Long affiliateCodeId);
}