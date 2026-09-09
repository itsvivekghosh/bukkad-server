package com.bhukkad.identity;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityUtilsTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private SecurityUtils utils;

    @Test
    void getCurrentUserId_returnsPrincipalId() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new TokenPrincipal(42L, "a@b.com", "customer"));

        assertThat(utils.getCurrentUserId()).isEqualTo(42L);
    }

    @Test
    void getCurrentUserEmail_returnsPrincipalEmail() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new TokenPrincipal(42L, "a@b.com", "customer"));

        assertThat(utils.getCurrentUserEmail()).isEqualTo("a@b.com");
    }

    @Test
    void getCurrentUser_loadsCustomerFromRepository() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new TokenPrincipal(42L, "a@b.com", "customer"));

        Customer customer = new Customer();
        customer.setId(42L);
        when(customerRepository.findById(42L)).thenReturn(Optional.of(customer));

        assertThat(utils.getCurrentUser()).isSameAs(customer);
        verify(customerRepository).findById(42L);
    }

    @Test
    void getCurrentUser_principalNotAuthenticated_throws() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authentication.isAuthenticated()).thenReturn(false);

        assertThatThrownBy(utils::getCurrentUserId)
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void isCurrentUser_matchingId_returnsTrue() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new TokenPrincipal(42L, "a@b.com", "customer"));

        assertThat(utils.isCurrentUser(42L)).isTrue();
    }

    @Test
    void isCurrentUser_nonMatchingId_returnsFalse() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new TokenPrincipal(42L, "a@b.com", "customer"));

        assertThat(utils.isCurrentUser(99L)).isFalse();
    }
}
