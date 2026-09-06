package com.bhukkad.admin.api;

import com.bhukkad.admin.AbstractAdminPostgresTest;
import com.bhukkad.admin.domain.ChurnScore;
import com.bhukkad.admin.domain.ChurnScoreRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the B1 API-parity batch over a real HTTP server
 * against the PostgreSQL Testcontainers stack:
 * security (401 without a token, 200 with a valid HS256 user token minted the
 * way identity issues them), then the full controller → service → PostgreSQL
 * path for each ported endpoint (churn high-risk, churn rescore, experiment
 * exposures, feature-flag get/set/list).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.auth.jwt.secret=0123456789abcdef0123456789abcdef",
        "app.auth.jwt.issuer=bhukkad-identity"
})
class AdminApiE2ETest extends AbstractAdminPostgresTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private ChurnScoreRepository churnRepository;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private RestClient authedClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt())
                .build();
    }

    /**
     * Mints an HS256 token with the same claims contract the gateway forwards
     * ({@code sub = numeric userId}, {@code scope} role claim) signed with the
     * test secret configured above.
     */
    private String adminJwt() {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("42")
                    .issuer("bhukkad-identity")
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .claim("email", "admin@bhukkad.test")
                    .claim("scope", "ADMIN")
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
            jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("token mint failed", e);
        }
    }

    @Test
    void endpointsRequireAuthentication() {
        org.springframework.web.client.HttpClientErrorException.Forbidden e = null;
        try {
            client().get().uri("/api/v1/admin/churn/high-risk")
                    .retrieve().toEntity(String.class);
            org.assertj.core.api.Assertions.fail("expected 401");
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized expected) {
            assertThat(expected.getStatusCode().value()).isEqualTo(401);
            assertThat(expected.getResponseBodyAsString()).contains("Authentication required");
        }
    }

    @Test
    void churnHighRiskReturnsPersistedScoreOverHttp() {
        ChurnScore score = new ChurnScore();
        score.setCustomerId(42L);
        score.setScore(0.81);
        score.setModelVersion("b1-e2e");
        score.setComputedAt(java.time.LocalDateTime.now());
        score.setFeaturesJson("days_inactive=42|orders_declining");
        churnRepository.saveAndFlush(score);

        String body = authedClient().get().uri("/api/v1/admin/churn/high-risk")
                .retrieve().body(String.class);

        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"userId\":42");
        assertThat(body).contains("\"score\":81");
        assertThat(body).contains("\"riskLevel\":\"HIGH\"");
        assertThat(body).contains("days_inactive=42|orders_declining");
    }

    @Test
    void churnRescoreTouchesPersistedRow() {
        ChurnScore score = new ChurnScore();
        score.setCustomerId(43L);
        score.setScore(0.61);
        score.setModelVersion("b1-e2e");
        score.setComputedAt(java.time.LocalDateTime.now());
        churnRepository.saveAndFlush(score);

        String body = authedClient().post().uri("/api/v1/admin/churn/rescore/43")
                .retrieve().body(String.class);

        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"userId\":43");
        assertThat(body).contains("\"riskLevel\":\"MEDIUM\"");
    }

    @Test
    void experimentExposuresEndpointServesEnvelope() {
        String body = authedClient().get()
                .uri("/api/v1/admin/experiments/checkout-cta-copy/exposures")
                .retrieve().body(String.class);

        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"traceId\"");
    }

    @Test
    void featureFlagLifecycleOverHttp() {
        // set → read-back → revert → read-back (stateless w.r.t. prior flag state)
        String set = authedClient().put()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/admin/feature-flags/b1-e2e-flag")
                        .queryParam("value", true).build())
                .retrieve().body(String.class);
        assertThat(set).contains("\"data\":true");

        String readBack = authedClient().get()
                .uri("/api/v1/admin/feature-flags/b1-e2e-flag")
                .retrieve().body(String.class);
        assertThat(readBack).contains("\"data\":true");

        String revert = authedClient().put()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/admin/feature-flags/b1-e2e-flag")
                        .queryParam("value", false).build())
                .retrieve().body(String.class);
        assertThat(revert).contains("\"data\":false");

        String list = authedClient().get().uri("/api/v1/admin/feature-flags")
                .retrieve().body(String.class);
        assertThat(list).contains("\"b1-e2e-flag\":false");
    }
}
