package com.bhukkad.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;
    private UserDetails user;

    @BeforeEach
    void setUp() throws Exception {
        String secret = Base64.getEncoder().encodeToString("test-secret-32bytes-minimum-length!!".getBytes());
        JwtSecretRotationService rotationService = new JwtSecretRotationService(secret, false);
        provider = new JwtTokenProvider(rotationService);
        setField(provider, "jwtExpirationMs", 60000L);
        setField(provider, "refreshExpirationMs", 604800000L);
        setField(provider, "mfaExpirationMs", 300000L);
        user = new User("bob", "password", List.of());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void generateAccessToken_roundTrip() {
        String token = provider.generateAccessToken(user);
        assertNotNull(token);
        assertEquals("bob", provider.extractUsername(token));
    }

    @Test
    void generateRefreshToken_hasRefreshTypeClaim() {
        String token = provider.generateRefreshToken(user);
        assertTrue(provider.isRefreshToken(token));
    }

    @Test
    void generateMfaToken_carriesUserId() {
        String token = provider.generateMfaToken(42L, "test@example.com");
        assertTrue(provider.validateMfaToken(token));
    }

    @Test
    void isTokenValid_returnsTrueForValidToken() {
        String token = provider.generateAccessToken(user);
        assertTrue(provider.isTokenValid(token, user));
    }

    @Test
    void isTokenValid_rejectsTokenForDifferentUser() {
        String token = provider.generateAccessToken(user);
        UserDetails other = new User("alice", "password", List.of());
        assertFalse(provider.isTokenValid(token, other));
    }

    @Test
    void validateToken_acceptsValidToken() {
        String token = provider.generateAccessToken(user);
        assertTrue(provider.validateToken(token));
    }

    @Test
    void validateToken_rejectsExpiredToken() throws Exception {
        setField(provider, "jwtExpirationMs", -1L);
        String token = provider.generateAccessToken(user);
        assertFalse(provider.validateToken(token));
    }
}