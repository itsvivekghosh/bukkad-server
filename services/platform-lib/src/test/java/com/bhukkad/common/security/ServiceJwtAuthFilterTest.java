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
    // ─── feature #5: fail-closed without a secret + observability metrics ──────

    @Test
    void internalPath_noSecretConfigured_withoutToken_rejected401() throws Exception {
        // The pass-through flip: enforce-internal-paths defaults true, so a
        // misconfigured deployment (missing SERVICE_JWT_SECRET) must fail
        // CLOSED on internal paths, never silently admit callers.
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret("");
        props.setAllowedServices("order,payment");
        ServiceJwtAuthFilter filter = new ServiceJwtAuthFilter(props);

        MockHttpServletResponse response = runFilter(filter, "/api/v1/internal/wallet/credit", null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication()).isNull();
    }

    @Test
    void internalPath_noSecretConfigured_withToken_rejected401() throws Exception {
        // An unverifiable header must not be trusted — fail closed.
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret("");
        props.setAllowedServices("order,payment");
        ServiceJwtAuthFilter filter = new ServiceJwtAuthFilter(props);

        MockHttpServletResponse response = runFilter(filter, "/api/v1/internal/wallet/credit",
                serviceToken("order"));
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void nonInternalPath_noSecretConfigured_passesThrough() throws Exception {
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret("");
        props.setAllowedServices("order,payment");
        ServiceJwtAuthFilter filter = new ServiceJwtAuthFilter(props);

        MockHttpServletResponse response = runFilter(filter, "/api/v1/orders", null);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejects_incrementServiceAuthRejectedMetric_withReason() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret(SECRET);
        props.setAllowedServices("order,payment");
        ServiceJwtAuthFilter filter = new ServiceJwtAuthFilter(props, registry);

        // absent
        runFilter(filter, "/api/v1/internal/wallet/credit", null);
        // invalid signature
        runFilter(filter, "/api/v1/internal/wallet/credit",
                serviceToken("order").substring(0, 20) + "AAAA");
        // disallowed subject
        runFilter(filter, "/api/v1/internal/wallet/credit", serviceToken("rogue-service"));

        assertThat(registry.get("service_auth_rejected").tag("reason", "absent")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("service_auth_rejected").tag("reason", "invalid")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("service_auth_rejected").tag("reason", "forbidden")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void weakSecret_rejectsWithWeakkeyReason() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret("too-short");
        props.setAllowedServices("order,payment");
        ServiceJwtAuthFilter filter = new ServiceJwtAuthFilter(props, registry);

        MockHttpServletResponse response = runFilter(filter, "/api/v1/internal/wallet/credit",
                serviceToken("order"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(registry.get("service_auth_rejected").tag("reason", "weakkey")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void internalPath_withoutToken_metricAbsentReason() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setJwtSecret("");
        ServiceJwtAuthFilter filter = new ServiceJwtAuthFilter(props, registry);

        runFilter(filter, "/api/v1/internal/jobs", null);
        assertThat(registry.get("service_auth_rejected").tag("reason", "absent")
                .counter().count()).isEqualTo(1.0);
    }
}