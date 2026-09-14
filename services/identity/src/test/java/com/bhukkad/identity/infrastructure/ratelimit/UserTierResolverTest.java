package com.bhukkad.identity.infrastructure.ratelimit;

import com.bhukkad.identity.domain.entity.CustomerMembership;
import com.bhukkad.identity.domain.entity.MembershipPlan;
import com.bhukkad.identity.domain.repository.CustomerMembershipRepository;
import com.bhukkad.identity.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tier mapping for tiered rate limits — every branch of
 * {@link UserTierResolver#resolveCurrentTier()}: unauthenticated error path,
 * null principal, no active membership, and the full 1/2/3/null/unknown
 * escalation ladder.
 */
class UserTierResolverTest {

    private SecurityUtils securityUtils;
    private CustomerMembershipRepository membershipRepository;
    private UserTierResolver resolver;

    @BeforeEach
    void setUp() {
        securityUtils = mock(SecurityUtils.class);
        membershipRepository = mock(CustomerMembershipRepository.class);
        resolver = new UserTierResolver(securityUtils, membershipRepository);
    }

    private void activeMembershipWithTier(Integer tierLevel) {
        var plan = mock(MembershipPlan.class);
        when(plan.getTierLevel()).thenReturn(tierLevel);
        var membership = mock(CustomerMembership.class);
        when(membership.getPlan()).thenReturn(plan);
        when(membershipRepository.findActiveMembership(eq(42L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(membership));
    }

    @Test
    void resolveCurrentTier_securityError_fallsBackToFree() {
        when(securityUtils.getCurrentUserId()).thenThrow(new IllegalStateException("no ctx"));
        assertThat(resolver.resolveCurrentTier()).isEqualTo(UserTierResolver.TIER_FREE);
    }

    @Test
    void resolveCurrentTier_nullPrincipal_fallsBackToFree() {
        when(securityUtils.getCurrentUserId()).thenReturn(null);
        assertThat(resolver.resolveCurrentTier()).isEqualTo(UserTierResolver.TIER_FREE);
    }

    @Test
    void resolveCurrentTier_noActiveMembership_isFree() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        when(membershipRepository.findActiveMembership(eq(42L), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        assertThat(resolver.resolveCurrentTier()).isEqualTo(UserTierResolver.TIER_FREE);
    }

    @Test
    void resolveCurrentTier_tierLevels_mapToPlanNames() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);

        activeMembershipWithTier(1);
        assertThat(resolver.resolveCurrentTier()).isEqualTo("silver");

        activeMembershipWithTier(2);
        assertThat(resolver.resolveCurrentTier()).isEqualTo("gold");

        activeMembershipWithTier(3);
        assertThat(resolver.resolveCurrentTier()).isEqualTo("platinum");

        activeMembershipWithTier(null);
        assertThat(resolver.resolveCurrentTier()).isEqualTo(UserTierResolver.TIER_FREE);

        activeMembershipWithTier(9);
        assertThat(resolver.resolveCurrentTier()).isEqualTo(UserTierResolver.TIER_FREE);
    }
}
