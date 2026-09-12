package com.bhukkad.referral.api.controller;

import com.bhukkad.referral.AbstractReferralPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level checks of {@code ReferralSecurityConfig}: public code validation
 * passes without credentials, the JSON 401 entry point answers for anonymous
 * authenticated surfaces, and the mesh {@code ServiceJwtAuthFilter} gates
 * {@code /internal/**} when the shared secret is configured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.service.jwt-secret=0123456789abcdef0123456789abcdef",
                "app.auth.service.allowed-services=identity,growth"
        })
class ReferralEndpointSecurityPostgresTest extends AbstractReferralPostgresTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void publicValidation_noToken_returns200False() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/referrals/validate/BKUNKNOWN", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("false");
    }

    @Test
    void anonymousSelfSurface_returns401JsonEntryPoint() {
        ResponseEntity<String> response =
                rest.postForEntity("/api/v1/referrals/generate", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void internalSurface_withoutServiceToken_returns401() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/referrals/internal/apply", "{ \"referralCode\": \"BK1\", \"customerId\": 1 }",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
