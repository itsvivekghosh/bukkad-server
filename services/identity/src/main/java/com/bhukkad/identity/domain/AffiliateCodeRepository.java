package com.bhukkad.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Port of the monolith {@code com.bhukkad.repository.AffiliateCodeRepository}
 * (WAVE 2).
 */
public interface AffiliateCodeRepository extends JpaRepository<AffiliateCode, Long> {

    Optional<AffiliateCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<AffiliateCode> findByIsActiveTrueOrderByCreatedAtDesc();
}
