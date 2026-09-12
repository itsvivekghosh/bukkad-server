package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.AffiliateReferral;

import com.bhukkad.identity.domain.entity.AffiliateReferral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Port of the monolith {@code com.bhukkad.repository.AffiliateReferralRepository}
 * (WAVE 2).
 */
public interface AffiliateReferralRepository extends JpaRepository<AffiliateReferral, Long> {

    long countByAffiliateCodeId(Long affiliateCodeId);

    long countByAffiliateCodeIdAndStatus(Long affiliateCodeId, AffiliateReferral.AffiliateReferralStatus status);

    boolean existsByAffiliateCodeIdAndCustomerId(Long affiliateCodeId, Long customerId);

    List<AffiliateReferral> findByAffiliateCodeIdOrderByCreatedAtDesc(Long affiliateCodeId);

    @Query("select coalesce(sum(r.rewardAmount), 0.0) from AffiliateReferral r where r.affiliateCode.id = :codeId")
    double sumRewardByAffiliateCodeId(@Param("codeId") Long affiliateCodeId);
}
