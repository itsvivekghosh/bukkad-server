package com.bhukkad.ratelimit;

import com.bhukkad.entity.CustomerMembership;
import com.bhukkad.entity.MembershipPlan;
import com.bhukkad.entity.User;
import com.bhukkad.repository.CustomerMembershipRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserTierResolverTest {

    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private CustomerMembershipRepository customerMembershipRepository;

    private UserTierResolver resolver() {
        return new UserTierResolver(securityUtils, customerMembershipRepository);
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private CustomerMembership membership(int tierLevel) {
        MembershipPlan plan = new MembershipPlan();
        plan.setTierLevel(tierLevel);
        CustomerMembership membership = new CustomerMembership();
        membership.setPlan(plan);
        return membership;
    }

    @Test
    void noActiveMembership_isFree() {
        when(securityUtils.getCurrentUser()).thenReturn(user(1L));
        when(customerMembershipRepository.findActiveMembership(eq(1L), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        assertEquals("free", resolver().resolveCurrentTier());
    }

    @Test
    void platinumMembership_isPlatinum() {
        when(securityUtils.getCurrentUser()).thenReturn(user(1L));
        when(customerMembershipRepository.findActiveMembership(eq(1L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(membership(3)));

        assertEquals("platinum", resolver().resolveCurrentTier());
    }

    @Test
    void goldMembership_isGold() {
        when(securityUtils.getCurrentUser()).thenReturn(user(1L));
        when(customerMembershipRepository.findActiveMembership(eq(1L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(membership(2)));

        assertEquals("gold", resolver().resolveCurrentTier());
    }

    @Test
    void basicTier_isFree() {
        when(securityUtils.getCurrentUser()).thenReturn(user(1L));
        when(customerMembershipRepository.findActiveMembership(eq(1L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(membership(0)));

        assertEquals("free", resolver().resolveCurrentTier());
    }
}
