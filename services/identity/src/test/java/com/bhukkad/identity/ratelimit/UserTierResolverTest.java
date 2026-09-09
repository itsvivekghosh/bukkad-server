package com.bhukkad.identity.ratelimit;

import com.bhukkad.identity.SecurityUtils;
import com.bhukkad.identity.domain.CustomerMembership;
import com.bhukkad.identity.domain.CustomerMembershipRepository;
import com.bhukkad.identity.domain.MembershipPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserTierResolverTest {

    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private CustomerMembershipRepository membershipRepository;

    @InjectMocks
    private UserTierResolver resolver;

    @Test
    void resolveCurrentTier_noUser_returnsFree() {
        when(securityUtils.getCurrentUserId()).thenThrow(new RuntimeException("not authed"));

        assertThat(resolver.resolveCurrentTier()).isEqualTo("free");
    }

    @Test
    void resolveCurrentTier_activeMembership_returnsTier() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);

        MembershipPlan plan = new MembershipPlan();
        plan.setTierLevel(2);
        CustomerMembership membership = new CustomerMembership();
        membership.setPlan(plan);
        when(membershipRepository.findActiveMembership(eq(10L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(membership));

        assertThat(resolver.resolveCurrentTier()).isEqualTo("gold");
    }

    @Test
    void resolveCurrentTier_noMembership_returnsFree() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(membershipRepository.findActiveMembership(any(Long.class), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolveCurrentTier()).isEqualTo("free");
    }
}
