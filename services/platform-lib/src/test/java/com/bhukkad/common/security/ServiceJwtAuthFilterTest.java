package com.bhukkad.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Post-audit regression tests: internal paths REQUIRE a valid service token,
 * and a valid service token yields ROLE_SERVICE.
 */
class ServiceJwtAuthFilterTest {

    private static final String SECRET = "service-mesh-secret-0123456789abcdef";
    private static final String OTHER_SECRET = "another-secret-0123456789abcdefghij";

    private ServiceJwtAuthFilter filter() {
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret(SECRET);
        props.setAllowedServices("order,payment");
        return new ServiceJwtAuthFilter(props);
    }

    private String serviceToken(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(key)
                .compact();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse runFilter(ServiceJwtAuthFilter filter, String path, String token)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (token != null) {
            request.addHeader(ServiceJwtAuthFilter.HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void internalPath_withoutToken_rejected401() throws Exception {
        MockHttpServletResponse response = runFilter(filter(), "/api/v1/internal/wallet/credit", null);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void internalPath_withUserJwtButNoServiceToken_rejected401() throws Exception {
        // The exact exploit from the audit: an ordinary user JWT must NOT open
        // internal money endpoints.
        MockHttpServletResponse response = runFilter(filter(), "/api/v1/internal/delivery/earnings", null);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void internalPath_withValidServiceToken_allowed() throws Exception {
        MockHttpServletResponse response = runFilter(filter(),
                "/api/v1/internal/wallet/credit", serviceToken("order"));
        assertThat(response.getStatus()).isNotEqualTo(401);
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_SERVICE");
    }

    @Test
    void internalPath_withWrongSecret_rejected401() throws Exception {
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret(OTHER_SECRET);
        props.setAllowedServices("order,payment");
        ServiceJwtAuthFilter filter = new ServiceJwtAuthFilter(props);

        MockHttpServletResponse response = runFilter(filter, "/api/v1/internal/wallet/credit",
                serviceToken("order"));
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void disallowedSubject_rejected403() throws Exception {
        MockHttpServletResponse response = runFilter(filter(),
                "/api/v1/internal/wallet/credit", serviceToken("rogue-service"));
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void nonInternalPath_withoutToken_passesThrough() throws Exception {
        MockHttpServletResponse response = runFilter(filter(), "/api/v1/orders", null);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
