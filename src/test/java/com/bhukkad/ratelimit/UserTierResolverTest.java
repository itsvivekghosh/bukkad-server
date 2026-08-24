package com.bhukkad.ratelimit;

import com.bhukkad.entity.CustomerMembership;
import com.bhukkad.entity.MembershipPlan;
import com.bhukkad.entity.User;
import com.bhukkad.repository.CustomerMembershipRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserTierResolver} — the rate-limit tier resolution
 * used by {@code RateLimitAspect} on every request. This locks in the
 * membership-tier mapping (free/silver/gold/platinum) and the fallback
 * behaviour for anonymous, admin, and membership-less users.
 */
@ExtendWith(MockitoExtension.class)
class UserTierResolverTest {

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private CustomerMembershipRepository customerMembershipRepository;

    @InjectMocks
    private UserTierResolver userTierResolver;

    private User user(Long id, User.UserRole role) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    private CustomerMembership membershipWithTier(Integer tierLevel) {
        MembershipPlan plan = new MembershipPlan();
        plan.setTierLevel(tierLevel);
        CustomerMembership membership = new CustomerMembership();
        membership.setPlan(plan);
        return membership;
    }

    @Test
    void anonymousUser_returnsFree() {
        when(securityUtils.getCurrentUser()).thenThrow(new RuntimeException("no auth"));
        assertEquals(UserTierResolver.TIER_FREE, userTierResolver.resolveCurrentTier());
    }

    @Test
    void nullUser_returnsFree() {
        when(securityUtils.getCurrentUser()).thenReturn(null);
        assertEquals(UserTierResolver.TIER_FREE, userTierResolver.resolveCurrentTier());
    }

    @Test
    void adminUser_returnsPlatinum() {
        when(securityUtils.getCurrentUser()).thenReturn(user(1L, User.UserRole.ADMIN));
        assertEquals("platinum", userTierResolver.resolveCurrentTier());
    }

    @Test
    void customerWithoutMembership_returnsFree() {
        when(securityUtils.getCurrentUser()).thenReturn(user(2L, User.UserRole.CUSTOMER));
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.empty());
        assertEquals(UserTierResolver.TIER_FREE, userTierResolver.resolveCurrentTier());
    }

    @Test
    void silverMembership_returnsSilver() {
        when(securityUtils.getCurrentUser()).thenReturn(user(2L, User.UserRole.CUSTOMER));
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(membershipWithTier(1)));
        assertEquals("silver", userTierResolver.resolveCurrentTier());
    }

    @Test
    void goldMembership_returnsGold() {
        when(securityUtils.getCurrentUser()).thenReturn(user(2L, User.UserRole.CUSTOMER));
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(membershipWithTier(2)));
        assertEquals("gold", userTierResolver.resolveCurrentTier());
    }

    @Test
    void platinumMembership_returnsPlatinum() {
        when(securityUtils.getCurrentUser()).thenReturn(user(2L, User.UserRole.CUSTOMER));
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(membershipWithTier(3)));
        assertEquals("platinum", userTierResolver.resolveCurrentTier());
    }

    @Test
    void nullTierLevel_returnsFree() {
        when(securityUtils.getCurrentUser()).thenReturn(user(2L, User.UserRole.CUSTOMER));
        when(customerMembershipRepository.findActiveMembership(anyLong(), any()))
                .thenReturn(Optional.of(membershipWithTier(null)));
        assertEquals(UserTierResolver.TIER_FREE, userTierResolver.resolveCurrentTier());
    }
}
