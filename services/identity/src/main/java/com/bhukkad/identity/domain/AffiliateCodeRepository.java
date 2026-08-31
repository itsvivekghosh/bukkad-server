package com.bhukkad.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AffiliateCodeRepository extends JpaRepository<AffiliateCode, Long> {
    Optional<AffiliateCode> findByCode(String code);
}