package com.bhukkad.membership;

import com.bhukkad.dto.request.SubscribeMembershipRequest;
import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.MembershipStatusResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.CustomerMembership;
import com.bhukkad.entity.MembershipPlan;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.CustomerMembershipRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.MembershipPlanRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MembershipService} — plan listing, subscription,
 * membership discount, and referral bonus logic.
 */
@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    @Mock
    private MembershipPlanRepository membershipPlanRepository;
    @Mock
    private CustomerMembershipRepository customerMembershipRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private MembershipService membershipService;

    private MembershipPlan activePlan(Long id, String name, int tierLevel, double discount,
                                      double maxDiscount, double referralBonus, int referralMax) {
        MembershipPlan p = new MembershipPlan();
        p.setId(id);
        p.setName(name);
        p.setIsActive(true);
        p.setPricePerMonth(199.0);
        p.setFreeDelivery(true);
        p.setDiscountPercent(discount);
        p.setMaxDiscountPercent(maxDiscount);
        p.setTierLevel(tierLevel);
        p.setReferralBonusPercent(referralBonus);
        p.setReferralMaxPerMonth(referralMax);
        return p;
    }

    private Customer customer(Long id) {
        Customer c = new Customer();
        c.setId(id);
        c.setLoyaltyPoints(0);
        return c;
    }

    @Test
    void listPlans_returnsOnlyActive() {
        when(membershipPlanRepository.findByIsActiveTrue())
                .thenReturn(List.of(activePlan(1L, "Gold", 2, 10.0, 15.0, 5.0, 10)));

        List<MembershipPlanResponse> plans = membershipService.listPlans();

        assertEquals(1, plans.size());
        assertEquals("Gold", plans.get(0).getName());
        assertEquals(2, plans.get(0).getTierLevel());
        assertEquals(199.0, plans.get(0).getPricePerMonth());
        assertTrue(plans.get(0).getFreeDelivery());
    }

    @Test
    void subscribe_addsNewActiveMembership() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(7L)));
        when(membershipPlanRepository.findById(10L))
                .thenReturn(Optional.of(activePlan(10L, "Silver", 1, 5.0, 10.0, 0, 0)));
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.empty());

        CustomerMembership saved = new CustomerMembership();
        saved.setId(100L);
        saved.setStatus(CustomerMembership.MembershipStatus.ACTIVE);
        when(customerMembershipRepository.save(any())).thenAnswer(inv -> {
            CustomerMembership m = inv.getArgument(0);
            m.setId(100L);
            return m;
        });

        SubscribeMembershipRequest request = new SubscribeMembershipRequest();
        request.setPlanId(10L);

        MembershipStatusResponse response = membershipService.subscribe(request);

        assertTrue(response.isActive());
        assertEquals(100L, response.getMembershipId());
        assertEquals("Silver", response.getPlanName());
        assertEquals(1, response.getTierLevel());
        verify(customerMembershipRepository).save(any());
    }

    @Test
    void subscribe_duplicate_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(7L)));
        when(membershipPlanRepository.findById(10L))
                .thenReturn(Optional.of(activePlan(10L, "Gold", 2, 10.0, 15.0, 0, 0)));
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(new CustomerMembership()));

        SubscribeMembershipRequest request = new SubscribeMembershipRequest();
        request.setPlanId(10L);

        assertThrows(BusinessException.class, () -> membershipService.subscribe(request));
    }

    @Test
    void subscribe_inactivePlan_throws() {
        MembershipPlan inactive = activePlan(10L, "Silver", 1, 5.0, 10.0, 0, 0);
        inactive.setIsActive(false);

        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(7L)));
        when(membershipPlanRepository.findById(10L)).thenReturn(Optional.of(inactive));

        SubscribeMembershipRequest request = new SubscribeMembershipRequest();
        request.setPlanId(10L);

        assertThrows(BusinessException.class, () -> membershipService.subscribe(request));
    }

    @Test
    void getActiveMembership_whenExists_returnsStatus() {
        CustomerMembership cm = new CustomerMembership();
        cm.setId(1L);
        cm.setStatus(CustomerMembership.MembershipStatus.ACTIVE);
        cm.setPlan(activePlan(2L, "Gold", 2, 10.0, 15.0, 0, 0));
        cm.setStartsAt(LocalDateTime.of(2026, 8, 1, 0, 0));
        cm.setEndsAt(LocalDateTime.of(2026, 9, 1, 0, 0));

        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(cm));

        MembershipStatusResponse response = membershipService.getActiveMembership(7L);

        assertTrue(response.isActive());
        assertEquals(1L, response.getMembershipId());
        assertEquals("Gold", response.getPlanName());
    }

    @Test
    void getActiveMembership_whenNone_returnsInactive() {
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.empty());

        MembershipStatusResponse response = membershipService.getActiveMembership(7L);

        assertFalse(response.isActive());
    }

    @Test
    void applyMembershipDiscount_appliesPlanDiscount() {
        MembershipPlan plan = activePlan(1L, "Gold", 2, 10.0, 15.0, 0, 0);
        CustomerMembership cm = new CustomerMembership();
        cm.setPlan(plan);

        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(cm));

        double discount = membershipService.applyMembershipDiscount(7L, 1000.0);
        // 10% of 1000 = 100.0
        assertEquals(100.0, discount, 0.01);
    }

    @Test
    void applyMembershipDiscount_capsAtMaxDiscountPercent() {
        MembershipPlan plan = activePlan(1L, "Gold", 2, 20.0, 10.0, 0, 0);
        CustomerMembership cm = new CustomerMembership();
        cm.setPlan(plan);

        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(cm));

        double discount = membershipService.applyMembershipDiscount(7L, 1000.0);
        // Max 10% = 100.0
        assertEquals(100.0, discount, 0.01);
    }

    @Test
    void applyMembershipDiscount_noMembership_returnsZero() {
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.empty());

        assertEquals(0.0, membershipService.applyMembershipDiscount(7L, 1000.0), 0.01);
    }

    @Test
    void addReferralBonus_addsPointsAndMarksReferrer() {
        MembershipPlan plan = activePlan(1L, "Gold", 2, 10.0, 15.0, 5.0, 10);
        Customer referrer = customer(1L);
        Customer newCustomer = customer(2L);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(referrer));
        when(customerRepository.findById(2L)).thenReturn(Optional.of(newCustomer));
        when(membershipPlanRepository.findFirstByIsActiveTrueOrderByIdAsc()).thenReturn(plan);
        when(customerMembershipRepository.countReferralsByReferrerThisMonth(anyLong(), any()))
                .thenReturn(0L);

        boolean result = membershipService.addReferralBonus(1L, 2L);

        assertTrue(result);
        assertEquals(50, referrer.getLoyaltyPoints());
        assertEquals(1L, newCustomer.getReferrerId());
        verify(customerRepository, times(2)).save(any());
    }

    @Test
    void addReferralBonus_alreadyReferred_returnsFalse() {
        Customer referrer = customer(1L);
        Customer newCustomer = customer(2L);
        newCustomer.setReferrerId(1L);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(referrer));
        when(customerRepository.findById(2L)).thenReturn(Optional.of(newCustomer));

        boolean result = membershipService.addReferralBonus(1L, 2L);
        assertFalse(result);
    }

    @Test
    void addReferralBonus_zeroBonus_returnsFalse() {
        MembershipPlan plan = activePlan(1L, "Gold", 2, 10.0, 15.0, 0.0, 10);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(customerRepository.findById(2L)).thenReturn(Optional.of(customer(2L)));
        when(membershipPlanRepository.findFirstByIsActiveTrueOrderByIdAsc()).thenReturn(plan);

        boolean result = membershipService.addReferralBonus(1L, 2L);
        assertFalse(result);
    }

    @Test
    void addReferralBonus_monthlyLimitReached_returnsFalse() {
        MembershipPlan plan = activePlan(1L, "Gold", 2, 10.0, 15.0, 5.0, 1);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(customerRepository.findById(2L)).thenReturn(Optional.of(customer(2L)));
        when(membershipPlanRepository.findFirstByIsActiveTrueOrderByIdAsc()).thenReturn(plan);
        when(customerMembershipRepository.countReferralsByReferrerThisMonth(anyLong(), any()))
                .thenReturn(1L);

        boolean result = membershipService.addReferralBonus(1L, 2L);
        assertFalse(result);
    }
}