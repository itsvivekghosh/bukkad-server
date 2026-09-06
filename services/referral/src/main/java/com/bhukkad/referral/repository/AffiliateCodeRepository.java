package com.bhukkad.referral.repository;

import com.bhukkad.referral.entity.AffiliateCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AffiliateCodeRepository extends JpaRepository<AffiliateCode, Long> {

    Optional<AffiliateCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<AffiliateCode> findByIsActiveTrueOrderByCreatedAtDesc();
}