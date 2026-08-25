package com.bhukkad.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;
    private UserDetails user;

    @BeforeEach
    void setUp() throws Exception {
        String secret = Base64.getEncoder().encodeToString(
                "test-secret-64bytes-minimum-length-for-hs512-signing-0123456789a".getBytes());
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
    void generateAccessToken_isSignedWithHS512() {
        String token = provider.generateAccessToken(user);
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        assertTrue(header.contains("\"alg\":\"HS512\""),
                "tokens must be signed with the strongest HMAC algorithm HS512, got: " + header);
    }

    @Test
    void generateToken_aliasForAccessToken() {
        String token = provider.generateToken(user);
        assertEquals("bob", provider.extractUsername(token));
    }

    @Test
    void generateToken_withExtraClaims() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("custom", "value");
        String token = provider.generateToken(extra, user);
        assertEquals("bob", provider.extractUsername(token));
        assertEquals("value", provider.extractClaim(token, claims -> claims.get("custom", String.class)));
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

    @Test
    void validateToken_rejectsInvalidSignature() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib2IifQ.invalidsignature";
        assertFalse(provider.validateToken(token));
    }

    @Test
    void validateToken_rejectsMalformedToken() {
        assertFalse(provider.validateToken("not-a-jwt"));
    }

    @Test
    void validateToken_rejectsUnsupportedToken() {
        // A token with a different algorithm header
        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJib2IifQ.";
        assertFalse(provider.validateToken(token));
    }

    @Test
    void validateToken_rejectsEmptyToken() {
        assertFalse(provider.validateToken(""));
    }

    @Test
    void isTokenValid_exceptionDuringValidation() {
        assertFalse(provider.isTokenValid("invalid-token", user));
    }

    @Test
    void extractUserId_fromMfaToken() {
        String token = provider.generateMfaToken(42L, "test@example.com");
        assertEquals(42L, provider.extractUserId(token));
    }

    @Test
    void extractUserId_returnsNullWhenNotPresent() {
        String token = provider.generateAccessToken(user);
        assertNull(provider.extractUserId(token));
    }

    @Test
    void validateMfaToken_nullToken() {
        assertFalse(provider.validateMfaToken(null));
    }

    @Test
    void validateMfaToken_blankToken() {
        assertFalse(provider.validateMfaToken("   "));
    }

    @Test
    void validateMfaToken_expiredToken() throws Exception {
        setField(provider, "mfaExpirationMs", -1L);
        String token = provider.generateMfaToken(42L, "test@example.com");
        assertFalse(provider.validateMfaToken(token));
    }

    @Test
    void validateMfaToken_notMfaType() {
        String token = provider.generateAccessToken(user);
        assertFalse(provider.validateMfaToken(token));
    }

    @Test
    void validateMfaToken_exception() {
        assertFalse(provider.validateMfaToken("invalid-token"));
    }

    @Test
    void isRefreshToken_returnsFalseOnException() {
        assertFalse(provider.isRefreshToken("invalid-token"));
    }

    @Test
    void getRemainingValidityMs_returnsPositive() {
        String token = provider.generateAccessToken(user);
        assertTrue(provider.getRemainingValidityMs(token) > 0);
    }

    @Test
    void getRemainingValidityMs_returnsZeroForExpired() throws Exception {
        setField(provider, "jwtExpirationMs", -1L);
        String token = provider.generateAccessToken(user);
        assertEquals(0, provider.getRemainingValidityMs(token));
    }

    @Test
    void extractClaim_returnsCorrectValue() {
        String token = provider.generateAccessToken(user);
        String sub = provider.extractClaim(token, claims -> claims.getSubject());
        assertEquals("bob", sub);
    }
}