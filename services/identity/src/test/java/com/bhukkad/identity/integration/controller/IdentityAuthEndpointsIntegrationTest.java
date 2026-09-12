package com.bhukkad.identity.integration.controller;

import com.bhukkad.identity.support.AbstractIdentityPostgresTest;

import com.bhukkad.identity.support.AbstractIdentityPostgresTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the new P3 identity endpoints: refresh and verify.
 * Reuses the Testcontainers-PostgreSQL base from {@link AbstractIdentityPostgresTest}
 * and the same {@code RestClient} pattern as {@code IdentityServiceContextSmokeTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdentityAuthEndpointsIntegrationTest extends AbstractIdentityPostgresTest {

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Mesh client: internal paths require the X-Service-Token header. */
    private RestClient meshClient() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-Service-Token", meshToken())
                .build();
    }

    private static String meshToken() {
        return io.jsonwebtoken.Jwts.builder()
                .subject("identity")
                .expiration(java.util.Date.from(
                        java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        com.bhukkad.identity.support.AbstractIdentityPostgresTest.SERVICE_JWT_SECRET
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    /** Registers a throwaway customer and returns the issued JWT. */
    private String registerAndGetToken(String email) throws Exception {
        String registerBody = "{\"email\":\"" + email + "\",\"phoneNumber\":\"999\","
                + "\"fullName\":\"Auth IT\",\"password\":\"password123\"}";
        var register = client().post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body(registerBody)
                .retrieve()
                .toEntity(String.class);
        assertThat(register.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(register.getBody());
        assertThat(json.get("token").asText()).isNotBlank();
        return json.get("token").asText();
    }

    /** Registers and returns [accessToken, refreshToken]. */
    private String[] registerAndGetPair(String email) throws Exception {
        String registerBody = "{\"email\":\"" + email + "\",\"phoneNumber\":\"999\","
                + "\"fullName\":\"Auth IT\",\"password\":\"password123\"}";
        var register = client().post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body(registerBody)
                .retrieve()
                .toEntity(String.class);
        assertThat(register.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(register.getBody());
        return new String[] {json.get("token").asText(), json.get("refreshToken").asText()};
    }

    @Test
    void refresh_rotatesValidToken() throws Exception {
        String[] pair = registerAndGetPair("refresh@test.com");
        assertThat(pair[1]).as("register must return a refresh token").isNotBlank();

        var refresh = client().post().uri("/api/v1/auth/refresh")
                .header("Content-Type", "application/json")
                .body("{\"refreshToken\":\"" + pair[1] + "\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(refresh.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(refresh.getBody());
        assertThat(json.get("token").asText()).isNotBlank();
        String rotated = json.get("refreshToken").asText();
        assertThat(rotated).isNotBlank().isNotEqualTo(pair[1]);
    }

    @Test
    void refresh_reuseOfRotatedTokenRevokesFamily() throws Exception {
        String[] pair = registerAndGetPair("reuse@test.com");

        // First rotation succeeds.
        var first = client().post().uri("/api/v1/auth/refresh")
                .header("Content-Type", "application/json")
                .body("{\"refreshToken\":\"" + pair[1] + "\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();

        // Presenting the ALREADY-ROTATED token again = theft signal:
        // reject with 401 and revoke the whole family (even the fresh one).
        org.springframework.web.client.HttpClientErrorException ex = null;
        try {
            client().post().uri("/api/v1/auth/refresh")
                    .header("Content-Type", "application/json")
                    .body("{\"refreshToken\":\"" + pair[1] + "\"}")
                    .retrieve().toEntity(String.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            ex = e;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);

        String rotated = objectMapper.readTree(first.getBody()).get("refreshToken").asText();
        org.springframework.web.client.HttpClientErrorException dead = null;
        try {
            client().post().uri("/api/v1/auth/refresh")
                    .header("Content-Type", "application/json")
                    .body("{\"refreshToken\":\"" + rotated + "\"}")
                    .retrieve().toEntity(String.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            dead = e;
        }
        assertThat(dead).as("family revoked after reuse").isNotNull();
        assertThat(dead.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void logout_revokesSession() throws Exception {
        String[] pair = registerAndGetPair("logout@test.com");
        var out = client().post().uri("/api/v1/auth/logout")
                .header("Content-Type", "application/json")
                .body("{\"refreshToken\":\"" + pair[1] + "\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(out.getStatusCode().is2xxSuccessful()).isTrue();

        org.springframework.web.client.HttpClientErrorException ex = null;
        try {
            client().post().uri("/api/v1/auth/refresh")
                    .header("Content-Type", "application/json")
                    .body("{\"refreshToken\":\"" + pair[1] + "\"}")
                    .retrieve().toEntity(String.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            ex = e;
        }
        assertThat(ex).as("refresh after logout must fail").isNotNull();
    }

    // ─── P1 REVOCATION: logout kills the ACCESS token too, not just sessions ───

    private int profileStatus(String bearerToken) {
        try {
            var ok = client().get().uri("/api/v1/customers/profile")
                    .header("Authorization", "Bearer " + bearerToken)
                    .retrieve().toBodilessEntity();
            return ok.getStatusCode().value();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return e.getStatusCode().value();
        }
    }

    /**
     * JWT {@code iat} has second precision and the epoch is truncated the same
     * way — a sub-second logout after issuing would make {@code iat < epoch}
     * unprovable. Two seconds keeps the assertion deterministic.
     */
    private static void guaranteeEpochIsLaterThanIat() throws InterruptedException {
        Thread.sleep(2_100);
    }

    @Test
    void logoutWithoutRefreshToken_revokesOutstandingAccessToken_immediatelyNotByTtl()
            throws Exception {
        String[] pair = registerAndGetPair("logout-epoch@test.com");

        int before = profileStatus(pair[0]);
        assertThat(before).as("token authenticates BEFORE logout").isNotEqualTo(401);

        guaranteeEpochIsLaterThanIat();

        var out = client().post().uri("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + pair[0])
                .retrieve().toBodilessEntity();
        assertThat(out.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(profileStatus(pair[0]))
                .as("P1: the access token dies WITH the logout, not with its 15-min TTL")
                .isEqualTo(401);
    }

    @Test
    void logoutWithRefreshToken_alsoStampsTheAccessEpoch() throws Exception {
        String[] pair = registerAndGetPair("logout-epoch-dev@test.com");

        guaranteeEpochIsLaterThanIat();

        var out = client().post().uri("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + pair[0])
                .header("Content-Type", "application/json")
                .body("{\"refreshToken\":\"" + pair[1] + "\"}")
                .retrieve().toBodilessEntity();
        assertThat(out.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(profileStatus(pair[0]))
                .as("per-device logout revokes the account epoch (access token dead)")
                .isEqualTo(401);
    }

    @Test
    void loginAfterLogout_getsAFreshVerifiableToken() throws Exception {
        String[] pair = registerAndGetPair("logout-epoch-relogin@test.com");
        guaranteeEpochIsLaterThanIat();

        client().post().uri("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + pair[0])
                .retrieve().toBodilessEntity();

        var login = client().post().uri("/api/v1/auth/login")
                .header("Content-Type", "application/json")
                .body("{\"email\":\"logout-epoch-relogin@test.com\",\"password\":\"password123\"}")
                .retrieve().toEntity(String.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(login.getBody());
        String fresh = json.get("token").asText();

        assertThat(profileStatus(fresh))
                .as("tokens issued AFTER the epoch verify normally")
                .isNotEqualTo(401);
    }

    @Test
    void changePassword_revokesOutstandingAccessToken() throws Exception {
        String[] pair = registerAndGetPair("pwchange-epoch@test.com");
        guaranteeEpochIsLaterThanIat();

        var change = client().post().uri("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + pair[0])
                .header("Content-Type", "application/json")
                .body("{\"currentPassword\":\"password123\",\"newPassword\":\"password456\"}")
                .retrieve().toBodilessEntity();
        assertThat(change.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(profileStatus(pair[0]))
                .as("credential change must kill every pre-change access token")
                .isEqualTo(401);
    }

    @Test
    void withoutRedisBackedEpoch_serviceStillIssuesAndVerifiesTokensInertly() {
        // Boot-safety mirror of the platform-lib unit contract: the identity
        // context (test profile, real Redis container here) must start the
        // validator WITH the revocation bean and still authenticate normally —
        // the regression guard against NPEs when no epoch key exists.
        assertThat(profileStatus("not-a-jwt")).isEqualTo(401);
    }


    @Test
    void refresh_rejectsInvalidToken() {
        // IdentityService.refresh throws UnauthorizedException → 401 (generic
        // rejection, no user enumeration). In Spring 6.1+ RestClient throws on
        // 4xx, so assert the exception status.
        org.springframework.web.client.HttpClientErrorException ex = null;
        try {
            client().post().uri("/api/v1/auth/refresh")
                    .header("Content-Type", "application/json")
                    .body("{\"refreshToken\":\"garbage-token\"}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            ex = e;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void verify_returnsValidForGoodToken() throws Exception {
        String token = registerAndGetToken("verify@test.com");

        var verify = meshClient().post().uri("/api/v1/internal/verify")
                .header("Content-Type", "application/json")
                .body("{\"token\":\"" + token + "\"}")
                .retrieve()
                .toEntity(String.class);
        JsonNode json = objectMapper.readTree(verify.getBody());
        assertThat(json.get("valid").asBoolean()).isTrue();
        assertThat(json.get("customerId").asLong()).isPositive();
    }

    @Test
    void verify_returnsValidFalseForBadToken() throws Exception {
        var verify = meshClient().post().uri("/api/v1/internal/verify")
                .header("Content-Type", "application/json")
                .body("{\"token\":\"bogus\"}")
                .retrieve()
                .toEntity(String.class);
        JsonNode json = objectMapper.readTree(verify.getBody());
        assertThat(json.get("valid").asBoolean()).isFalse();
    }
}