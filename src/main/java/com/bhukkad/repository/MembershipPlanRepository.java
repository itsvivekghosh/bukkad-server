package com.bhukkad.repository;

import com.bhukkad.entity.MembershipPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {
    List<MembershipPlan> findByIsActiveTrue();

    MembershipPlan findFirstByIsActiveTrueOrderByIdAsc();
}
