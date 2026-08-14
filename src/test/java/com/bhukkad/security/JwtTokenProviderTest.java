package com.bhukkad.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private static final String JWT_SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final String OTHER_SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5971";

    private JwtTokenProvider jwtTokenProvider;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationMs", 86400000L);
        userDetails = User.withUsername("user@example.com")
                .password("password")
                .roles("CUSTOMER")
                .build();
    }

    @Test
    void generateToken_andExtractUsername() {
        String token = jwtTokenProvider.generateToken(userDetails);

        assertNotNull(token);
        assertEquals("user@example.com", jwtTokenProvider.extractUsername(token));
    }

    @Test
    void generateToken_withExtraClaims() {
        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("role", "CUSTOMER");

        String token = jwtTokenProvider.generateToken(extraClaims, userDetails);

        assertEquals("CUSTOMER", jwtTokenProvider.extractClaim(token, claims -> claims.get("role", String.class)));
        assertEquals("user@example.com", jwtTokenProvider.extractClaim(token, Claims::getSubject));
    }

    @Test
    void extractClaim_expirationIsInFuture() {
        String token = jwtTokenProvider.generateToken(userDetails);

        assertTrue(jwtTokenProvider.extractClaim(token, Claims::getExpiration).after(new java.util.Date()));
    }

    @Test
    void isTokenValid_true() {
        String token = jwtTokenProvider.generateToken(userDetails);

        assertTrue(jwtTokenProvider.isTokenValid(token, userDetails));
    }

    @Test
    void isTokenValid_falseWhenUsernameMismatch() {
        String token = jwtTokenProvider.generateToken(userDetails);
        UserDetails other = User.withUsername("other@example.com")
                .password("password")
                .roles("CUSTOMER")
                .build();

        assertFalse(jwtTokenProvider.isTokenValid(token, other));
    }

    @Test
    void isTokenValid_falseWhenExpiredAndUsernameMatches() {
        JwtTokenProvider spy = org.mockito.Mockito.spy(jwtTokenProvider);
        String token = jwtTokenProvider.generateToken(userDetails);
        org.mockito.Mockito.doReturn("user@example.com").when(spy).extractUsername(token);
        org.mockito.Mockito.doReturn(new java.util.Date(0)).when(spy)
                .extractClaim(org.mockito.ArgumentMatchers.eq(token), org.mockito.ArgumentMatchers.any());

        assertFalse(spy.isTokenValid(token, userDetails));
    }

    @Test
    void isTokenValid_falseOnException() {
        assertFalse(jwtTokenProvider.isTokenValid("not-a-jwt", userDetails));
        assertFalse(jwtTokenProvider.isTokenValid(null, userDetails));
    }

    @Test
    void validateToken_success() {
        String token = jwtTokenProvider.generateToken(userDetails);

        assertTrue(jwtTokenProvider.validateToken(token));
    }

    @Test
    void validateToken_signatureException() {
        String token = jwtTokenProvider.generateToken(userDetails);
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", OTHER_SECRET);

        assertFalse(jwtTokenProvider.validateToken(token));
    }

    @Test
    void validateToken_malformedJwtException() {
        assertFalse(jwtTokenProvider.validateToken("not-a-jwt"));
    }

    @Test
    void validateToken_expiredJwtException() throws InterruptedException {
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationMs", 1L);
        String token = jwtTokenProvider.generateToken(userDetails);
        Thread.sleep(20);

        assertFalse(jwtTokenProvider.validateToken(token));
        assertFalse(jwtTokenProvider.isTokenValid(token, userDetails));
    }

    @Test
    void validateToken_illegalArgumentException() {
        assertFalse(jwtTokenProvider.validateToken(""));
        assertFalse(jwtTokenProvider.validateToken(null));
    }

    @Test
    void validateToken_unsupportedJwtException() {
        String unsigned = Jwts.builder().setSubject("user@example.com").compact();

        assertFalse(jwtTokenProvider.validateToken(unsigned));
    }
}
