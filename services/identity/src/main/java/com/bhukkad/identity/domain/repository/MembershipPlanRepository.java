package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.MembershipPlan;

import com.bhukkad.identity.domain.entity.MembershipPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Port of the monolith {@code com.bhukkad.repository.MembershipPlanRepository}
 * (WAVE 2).
 */
public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {

    List<MembershipPlan> findByIsActiveTrue();

    MembershipPlan findFirstByIsActiveTrueOrderByIdAsc();
}
