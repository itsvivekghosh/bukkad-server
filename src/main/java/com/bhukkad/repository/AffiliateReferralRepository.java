package com.bhukkad.repository;

import com.bhukkad.entity.AffiliateReferral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AffiliateReferralRepository extends JpaRepository<AffiliateReferral, Long> {

    long countByAffiliateCodeId(Long affiliateCodeId);

    long countByAffiliateCodeIdAndStatus(Long affiliateCodeId, AffiliateReferral.AffiliateReferralStatus status);

    boolean existsByAffiliateCodeIdAndCustomerId(Long affiliateCodeId, Long customerId);

    List<AffiliateReferral> findByAffiliateCodeIdOrderByCreatedAtDesc(Long affiliateCodeId);

    @Query("select coalesce(sum(r.rewardAmount), 0.0) from AffiliateReferral r where r.affiliateCode.id = :codeId")
    double sumRewardByAffiliateCodeId(@Param("codeId") Long affiliateCodeId);
}
