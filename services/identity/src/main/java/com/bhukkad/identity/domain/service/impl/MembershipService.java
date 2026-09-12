package com.bhukkad.identity.domain.service.impl;

import com.bhukkad.identity.api.dto.request.SubscribeMembershipRequest;
import com.bhukkad.identity.api.dto.response.MembershipPlanResponse;
import com.bhukkad.identity.api.dto.response.MembershipStatusResponse;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.entity.CustomerMembership;
import com.bhukkad.identity.domain.entity.MembershipPlan;
import com.bhukkad.identity.domain.repository.CustomerMembershipRepository;
import com.bhukkad.identity.domain.repository.CustomerRepository;
import com.bhukkad.identity.domain.repository.MembershipPlanRepository;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.entity.CustomerMembership;
import com.bhukkad.identity.domain.repository.CustomerMembershipRepository;
import com.bhukkad.identity.domain.repository.CustomerRepository;
import com.bhukkad.identity.domain.entity.MembershipPlan;
import com.bhukkad.identity.domain.repository.MembershipPlanRepository;
import com.bhukkad.identity.api.dto.request.SubscribeMembershipRequest;
import com.bhukkad.identity.api.dto.response.MembershipPlanResponse;
import com.bhukkad.identity.api.dto.response.MembershipStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Port of the monolith {@code com.bhukkad.membership.MembershipService}
 * (WAVE 2): plan listing, subscription, and order discounts.
 *
 * <p>Adaptations for the identity service:
 * <ul>
 *   <li>{@code SecurityUtils.getCurrentUserId()} is replaced by an explicit
 *       {@code customerId} argument — identity controllers resolve the caller
 *       from the authenticated principal and pass the id down (same pattern as
 *       {@code AccountProfileService}).</li>
 *   <li>{@code PriceCalculator} discount/round math is inlined (two trivial
 *       pure functions).</li>
 *   <li>{@code addReferralBonus} is NOT ported: its monthly-cap query joins
 *       the order aggregate (order domain) and it mutates loyalty points on
 *       the customer aggregate — both stay in the monolith working copy until
 *       those domains are extracted.</li>
 * </ul></p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipService {

    private final MembershipPlanRepository membershipPlanRepository;
    private final CustomerMembershipRepository customerMembershipRepository;
    private final CustomerRepository customerRepository;

    /**
     * Lists all active membership plans.
     *
     * @return available plans
     */
    public List<MembershipPlanResponse> listPlans() {
        return membershipPlanRepository.findByIsActiveTrue().stream()
                .map(this::toPlanResponse)
                .toList();
    }

    /**
     * Subscribes the customer to a membership plan for one month.
     *
     * @param customerId authenticated customer id
     * @param request    subscription request with plan ID
     * @return active membership status
     */
    @Transactional
    public MembershipStatusResponse subscribe(Long customerId, SubscribeMembershipRequest request) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        MembershipPlan plan = membershipPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Membership plan not found"));

        if (!Boolean.TRUE.equals(plan.getIsActive())) {
            throw new BusinessException("Membership plan is not available");
        }

        customerMembershipRepository.findActiveMembership(customerId, LocalDateTime.now())
                .ifPresent(existing -> {
                    throw new BusinessException("Customer already has an active membership");
                });

        LocalDateTime now = LocalDateTime.now();
        CustomerMembership membership = new CustomerMembership();
        membership.setCustomer(customer);
        membership.setPlan(plan);
        membership.setStatus(CustomerMembership.MembershipStatus.ACTIVE);
        membership.setStartsAt(now);
        membership.setEndsAt(now.plusMonths(1));

        return toStatusResponse(customerMembershipRepository.save(membership));
    }

    /**
     * Returns the active membership for a customer, if any.
     *
     * @param customerId customer identifier
     * @return membership status
     */
    public MembershipStatusResponse getActiveMembership(Long customerId) {
        return customerMembershipRepository.findActiveMembership(customerId, LocalDateTime.now())
                .map(this::toStatusResponse)
                .orElse(MembershipStatusResponse.builder().active(false).build());
    }

    /**
     * Applies membership discount to an order subtotal based on plan tier.
     * Inlines the monolith {@code PriceCalculator} discount + rounding math.
     *
     * @param customerId customer identifier
     * @param subtotal   order subtotal before discount
     * @return discount amount to deduct
     */
    public double applyMembershipDiscount(Long customerId, double subtotal) {
        return customerMembershipRepository.findActiveMembership(customerId, LocalDateTime.now())
                .map(membership -> {
                    MembershipPlan plan = membership.getPlan();
                    double percent = plan.getDiscountPercent();

                    // Apply tier max discount if configured
                    if (plan.getMaxDiscountPercent() != null && percent > plan.getMaxDiscountPercent()) {
                        percent = plan.getMaxDiscountPercent();
                    }

                    return roundToTwoDecimals(calculateDiscount(subtotal, percent));
                })
                .orElse(0.0);
    }

    private MembershipPlanResponse toPlanResponse(MembershipPlan plan) {
        return MembershipPlanResponse.builder()
                .id(plan.getId())
                .name(plan.getName())
                .description(plan.getDescription())
                .pricePerMonth(plan.getPricePerMonth())
                .freeDelivery(plan.getFreeDelivery())
                .discountPercent(plan.getDiscountPercent())
                .tierLevel(plan.getTierLevel())
                .maxDiscountPercent(plan.getMaxDiscountPercent())
                .referralBonusPercent(plan.getReferralBonusPercent())
                .referralMaxPerMonth(plan.getReferralMaxPerMonth())
                .build();
    }

    private MembershipStatusResponse toStatusResponse(CustomerMembership membership) {
        MembershipPlan plan = membership.getPlan();
        return MembershipStatusResponse.builder()
                .active(membership.getStatus() == CustomerMembership.MembershipStatus.ACTIVE)
                .membershipId(membership.getId())
                .planId(plan.getId())
                .planName(plan.getName())
                .status(membership.getStatus().name())
                .freeDelivery(plan.getFreeDelivery())
                .discountPercent(plan.getDiscountPercent())
                .tierLevel(plan.getTierLevel())
                .startsAt(membership.getStartsAt() != null ? membership.getStartsAt().toString() : null)
                .endsAt(membership.getEndsAt() != null ? membership.getEndsAt().toString() : null)
                .build();
    }

    // Inlined monolith PriceCalculator helpers (com.bhukkad.util.PriceCalculator)

    private static double calculateDiscount(double amount, double discountPercentage) {
        return amount * (discountPercentage / 100);
    }

    private static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
