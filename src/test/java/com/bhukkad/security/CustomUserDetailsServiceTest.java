package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new CustomUserDetailsService(userRepository);
    }

    @Test
    void loadUserByUsername_foundActive() {
        User user = buildUser(User.UserRole.CUSTOMER, true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("user@example.com");

        assertEquals("user@example.com", details.getUsername());
        assertEquals("encoded", details.getPassword());
        assertTrue(details.isEnabled());
        assertTrue(details.isAccountNonExpired());
        assertTrue(details.isAccountNonLocked());
        assertTrue(details.isCredentialsNonExpired());
        assertEquals("ROLE_CUSTOMER", details.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void loadUserByUsername_foundInactive() {
        User user = buildUser(User.UserRole.CUSTOMER, false);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("user@example.com");

        assertFalse(details.isEnabled());
    }

    @Test
    void loadUserByUsername_notFound() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        UsernameNotFoundException ex = assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("missing@example.com"));
        assertTrue(ex.getMessage().contains("missing@example.com"));
    }

    @ParameterizedTest
    @EnumSource(User.UserRole.class)
    void loadUserByUsername_mapsEachRoleToAuthority(User.UserRole role) {
        User user = buildUser(role, true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("user@example.com");

        assertEquals(1, details.getAuthorities().size());
        assertTrue(details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_" + role.name())));
    }

    private User buildUser(User.UserRole role, boolean active) {
        User user = new User();
        user.setEmail("user@example.com");
        user.setPassword("encoded");
        user.setRole(role);
        user.setActive(active);
        return user;
    }
}
