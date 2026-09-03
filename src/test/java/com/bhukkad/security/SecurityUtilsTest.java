package com.bhukkad.security;

import com.bhukkad.entity.Customer;
import com.bhukkad.common.error.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityUtilsTest {

    @Mock
    private AccountLookupService accountLookupService;

    private SecurityUtils securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = new SecurityUtils(accountLookupService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUser_nullAuthentication() {
        UnauthorizedException ex = assertThrows(UnauthorizedException.class, securityUtils::getCurrentUser);
        assertEquals("User not authenticated", ex.getMessage());
    }

    @Test
    void getCurrentUser_notAuthenticated() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, securityUtils::getCurrentUser);
        assertEquals("User not authenticated", ex.getMessage());
    }

    @Test
    void getCurrentUser_anonymousUser() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("anonymousUser");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, securityUtils::getCurrentUser);
        assertEquals("User not authenticated", ex.getMessage());
    }

    @Test
    void getCurrentUser_userNotInDatabase() {
        setAuthenticated("missing@example.com");
        when(accountLookupService.byIdentifier("missing@example.com")).thenReturn(Optional.empty());

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, securityUtils::getCurrentUser);
        assertEquals("User not found: missing@example.com", ex.getMessage());
    }

    @Test
    void getCurrentUser_success() {
        Customer customer = persistedCustomer();
        setAuthenticated("user@example.com");
        when(accountLookupService.byIdentifier("user@example.com")).thenReturn(Optional.of(customer));

        Customer result = (Customer) securityUtils.getCurrentUser();

        assertEquals(customer, result);
        assertEquals(10L, securityUtils.getCurrentUserId());
        assertEquals("user@example.com", securityUtils.getCurrentUserEmail());
    }

    @Test
    void getCurrentUser_phoneFirstUser_resolvesByPhone() {
        Customer phoneCustomer = new Customer();
        phoneCustomer.setId(20L);
        phoneCustomer.setPhoneNumber("9999999999");
        phoneCustomer.setEmail(null);
        setAuthenticated("9999999999");
        when(accountLookupService.byIdentifier("9999999999")).thenReturn(Optional.of(phoneCustomer));

        Customer result = (Customer) securityUtils.getCurrentUser();

        assertEquals(phoneCustomer, result);
        assertEquals(20L, securityUtils.getCurrentUserId());
        assertNull(securityUtils.getCurrentUserEmail());
    }

    @Test
    void getCurrentUserId_notAuthenticated_throws() {
        SecurityContextHolder.clearContext();
        assertThrows(UnauthorizedException.class, () -> securityUtils.getCurrentUserId());
    }

    @Test
    void getCurrentUserEmail_notAuthenticated_throws() {
        SecurityContextHolder.clearContext();
        assertThrows(UnauthorizedException.class, () -> securityUtils.getCurrentUserEmail());
    }

    @Test
    void isCurrentUser_true() {
        setAuthenticated("user@example.com");
        when(accountLookupService.byIdentifier("user@example.com"))
                .thenReturn(Optional.of(persistedCustomer()));

        assertTrue(securityUtils.isCurrentUser(10L));
    }

    @Test
    void isCurrentUser_falseWhenDifferentId() {
        setAuthenticated("user@example.com");
        when(accountLookupService.byIdentifier("user@example.com"))
                .thenReturn(Optional.of(persistedCustomer()));

        assertFalse(securityUtils.isCurrentUser(99L));
    }

    @Test
    void isCurrentUser_falseOnException() {
        SecurityContextHolder.clearContext();

        assertFalse(securityUtils.isCurrentUser(10L));
    }

    private Customer persistedCustomer() {
        Customer customer = new Customer();
        customer.setId(10L);
        customer.setEmail("user@example.com");
        customer.setRole(Customer.UserRole.CUSTOMER);
        return customer;
    }

    private void setAuthenticated(String principal) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
