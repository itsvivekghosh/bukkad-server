package com.bhukkad.common.security;

import com.bhukkad.common.error.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature #6: the shared principal accessor other modules were directed to
 * use when their module lacks one.
 */
class SecurityUtilsTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(TokenPrincipal principal, String... authorities) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void currentUserId_returnsSubject() {
        authenticate(new TokenPrincipal(42L, "a@b.com", "customer"), "ROLE_CUSTOMER");
        assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
        assertThat(SecurityUtils.currentUserIdOrNull()).isEqualTo(42L);
    }

    @Test
    void currentUserId_withoutAuthentication_throwsUnauthorized() {
        assertThatThrownBy(SecurityUtils::currentUserId)
                .isInstanceOf(UnauthorizedException.class);
        assertThat(SecurityUtils.currentUserIdOrNull()).isNull();
        assertThat(SecurityUtils.currentPrincipal()).isEmpty();
    }

    @Test
    void isAdmin_scopeAdmin_true() {
        authenticate(new TokenPrincipal(1L, "a@b.com", "ADMIN"));
        assertThat(SecurityUtils.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_roleClaimTrueWhenScopeAbsent() {
        authenticate(new TokenPrincipal(1L, "a@b.com", null), "ROLE_ADMIN");
        assertThat(SecurityUtils.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_customer_false() {
        authenticate(new TokenPrincipal(2L, "a@b.com", "CUSTOMER"), "ROLE_CUSTOMER");
        assertThat(SecurityUtils.isAdmin()).isFalse();
    }

    @Test
    void isAdmin_unauthenticated_false() {
        assertThat(SecurityUtils.isAdmin()).isFalse();
    }
}
