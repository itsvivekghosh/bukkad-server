package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.UserRepository;
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
    private UserRepository userRepository;

    private SecurityUtils securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = new SecurityUtils(userRepository);
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
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, securityUtils::getCurrentUser);
        assertEquals("User not found: missing@example.com", ex.getMessage());
    }

    @Test
    void getCurrentUser_success() {
        User user = persistedUser();
        setAuthenticated("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        User result = securityUtils.getCurrentUser();

        assertEquals(user, result);
        assertEquals(10L, securityUtils.getCurrentUserId());
        assertEquals("user@example.com", securityUtils.getCurrentUserEmail());
    }

    @Test
    void isCurrentUser_true() {
        setAuthenticated("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(persistedUser()));

        assertTrue(securityUtils.isCurrentUser(10L));
    }

    @Test
    void isCurrentUser_falseWhenDifferentId() {
        setAuthenticated("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(persistedUser()));

        assertFalse(securityUtils.isCurrentUser(99L));
    }

    @Test
    void isCurrentUser_falseOnException() {
        SecurityContextHolder.clearContext();

        assertFalse(securityUtils.isCurrentUser(10L));
    }

    private void setAuthenticated(String email) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(email, "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private User persistedUser() {
        User user = new User();
        user.setId(10L);
        user.setEmail("user@example.com");
        return user;
    }
}
