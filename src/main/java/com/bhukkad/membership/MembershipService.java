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
import java.util.Set;

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
     * Applies membership discount to an order subtotal based on plan tier.
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
                    
                    return PriceCalculator.roundToTwoDecimals(
                            PriceCalculator.calculateDiscount(subtotal, percent));
                })
                .orElse(0.0);
    }

    /**
     * Adds referral bonus points/credits to a referrer's account.
     *
     * @param referrerId      customer who made the referral
     * @param newCustomerId  customer who was referred
     * @return true if bonus was added successfully
     */
    @Transactional
    public boolean addReferralBonus(Long referrerId, Long newCustomerId) {
        Customer referrer = customerRepository.findById(referrerId)
                .orElseThrow(() -> new ResourceNotFoundException("Referrer not found"));
        Customer newCustomer = customerRepository.findById(newCustomerId)
                .orElseThrow(() -> new ResourceNotFoundException("New customer not found"));

        // Check if new customer is not already referred by this referrer
        if (newCustomer.getReferrerId() != null && newCustomer.getReferrerId().equals(referrerId)) {
            return false; // Already referred by this person
        }

        MembershipPlan plan = membershipPlanRepository.findFirstByIsActiveTrueOrderByIdAsc();
        if (plan == null) {
            throw new ResourceNotFoundException("Active membership plan not found");
        }

        double referralBonus = plan.getReferralBonusPercent();
        if (referralBonus <= 0) {
            return false; // No referral bonus configured
        }

        // Check monthly limit
        long referralsThisMonth = customerMembershipRepository.countReferralsByReferrerThisMonth(
                referrerId, LocalDateTime.now().withDayOfMonth(1));

        if (referralBonus > 0 && referralsThisMonth >= plan.getReferralMaxPerMonth()) {
            return false; // Monthly limit reached
        }

        // Add loyalty points to referrer
        referrer.setLoyaltyPoints(referrer.getLoyaltyPoints() + 50); // 50 points per referral

        customerRepository.save(referrer);

        // Mark new customer as referred
        newCustomer.setReferrerId(referrerId);
        customerRepository.save(newCustomer);

        return true;
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
}
