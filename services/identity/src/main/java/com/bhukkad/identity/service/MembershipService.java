package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.identity.domain.CustomerMembership;
import com.bhukkad.identity.domain.CustomerMembershipRepository;
import com.bhukkad.identity.domain.MembershipPlan;
import com.bhukkad.identity.domain.MembershipPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tiered membership (Priority 1): subscribe a customer to a plan.
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final MembershipPlanRepository planRepository;
    private final CustomerMembershipRepository membershipRepository;

    @Transactional(readOnly = true)
    public List<MembershipPlan> activePlans() {
        return planRepository.findByActiveTrue();
    }

    @Transactional
    public CustomerMembership subscribe(Long customerId, Long planId) {
        MembershipPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException("Plan not found: " + planId));
        if (!Boolean.TRUE.equals(plan.getActive())) {
            throw new BusinessException("Plan not active: " + planId);
        }
        CustomerMembership membership = new CustomerMembership();
        membership.setCustomerId(customerId);
        membership.setPlanId(planId);
        membership.setStatus("ACTIVE");
        membership.setStartedAt(LocalDateTime.now());
        membership.setExpiresAt(LocalDateTime.now().plusDays(30));
        return membershipRepository.save(membership);
    }

    @Transactional(readOnly = true)
    public CustomerMembership current(Long customerId) {
        return membershipRepository
                .findFirstByCustomerIdAndStatusOrderByExpiresAtDesc(customerId, "ACTIVE")
                .orElse(null);
    }
}