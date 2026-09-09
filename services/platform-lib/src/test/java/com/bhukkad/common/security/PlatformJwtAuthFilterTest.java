package com.bhukkad.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformJwtAuthFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String token(long userId, String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .claim("email", "u@b.com")
                .claim("scope", scope)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));
        return jwt.serialize();
    }

    @Test
    void validToken_setsAuthenticationAndAuthorities() throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(
                new PlatformJwtValidator(new PlatformJwtProperties(SECRET, null, null, null)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader("Authorization", "Bearer " + token(7L, "customer"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(TokenPrincipal.class);
        TokenPrincipal principal = (TokenPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.scope()).isEqualTo("customer");
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void invalidToken_leavesContextEmpty() throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(
                new PlatformJwtValidator(new PlatformJwtProperties(SECRET, null, null, null)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader("Authorization", "Bearer not-a-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingHeader_leavesContextEmpty() throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(
                new PlatformJwtValidator(new PlatformJwtProperties(SECRET, null, null, null)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
