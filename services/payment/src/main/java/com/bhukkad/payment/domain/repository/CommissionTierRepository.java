package com.bhukkad.payment.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bhukkad.payment.domain.entity.CommissionTier;
import java.util.List;

public interface CommissionTierRepository extends JpaRepository<CommissionTier, Long> {
    List<CommissionTier> findByActiveTrueOrderByMinOrderCountAsc();
}