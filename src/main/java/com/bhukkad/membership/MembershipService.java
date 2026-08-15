package com.bhukkad.membership;

import com.bhukkad.dto.request.SubscribeMembershipRequest;
import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.MembershipStatusResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.CustomerMembership;
import com.bhukkad.entity.MembershipPlan;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerMembershipRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.MembershipPlanRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages Bhukkad membership plans, subscriptions, and order discounts.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipService {

    private final MembershipPlanRepository membershipPlanRepository;
    private final CustomerMembershipRepository customerMembershipRepository;
    private final CustomerRepository customerRepository;
    private final SecurityUtils securityUtils;

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
     * Subscribes the authenticated customer to a membership plan for one month.
     *
     * @param request subscription request with plan ID
     * @return active membership status
     */
    @Transactional
    public MembershipStatusResponse subscribe(SubscribeMembershipRequest request) {
        Long customerId = securityUtils.getCurrentUserId();
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
     * Applies membership discount to an order subtotal.
     *
     * @param customerId customer identifier
     * @param subtotal   order subtotal before discount
     * @return discount amount to deduct
     */
    public double applyMembershipDiscount(Long customerId, double subtotal) {
        return customerMembershipRepository.findActiveMembership(customerId, LocalDateTime.now())
                .map(membership -> {
                    Double percent = membership.getPlan().getDiscountPercent();
                    if (percent == null || percent <= 0) {
                        return 0.0;
                    }
                    return PriceCalculator.roundToTwoDecimals(
                            PriceCalculator.calculateDiscount(subtotal, percent));
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
                .startsAt(membership.getStartsAt() != null ? membership.getStartsAt().toString() : null)
                .endsAt(membership.getEndsAt() != null ? membership.getEndsAt().toString() : null)
                .build();
    }
}
