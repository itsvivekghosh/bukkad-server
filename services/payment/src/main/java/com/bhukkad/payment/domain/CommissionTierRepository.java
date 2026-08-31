package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommissionTierRepository extends JpaRepository<CommissionTier, Long> {
    List<CommissionTier> findByActiveTrueOrderByMinOrderCountAsc();
}