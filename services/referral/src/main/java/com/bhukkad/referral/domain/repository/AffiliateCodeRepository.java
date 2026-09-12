package com.bhukkad.referral.domain.repository;

import com.bhukkad.referral.domain.entity.AffiliateCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

@Repository
public interface AffiliateCodeRepository extends JpaRepository<AffiliateCode, Long> {

    Optional<AffiliateCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<AffiliateCode> findByIsActiveTrueOrderByCreatedAtDesc();

    /** PERF-3: bounded newest-first listing (SQL ORDER BY + page cap). */
    List<AffiliateCode> findAllByOrderByCreatedAtDesc(Pageable pageable);
}