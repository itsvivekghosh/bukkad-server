package com.bhukkad.identity.api;

import com.bhukkad.identity.AbstractIdentityPostgresTest;
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

    @Test
    void refresh_rotatesValidToken() throws Exception {
        String token = registerAndGetToken("refresh@test.com");

        var refresh = client().post().uri("/api/v1/auth/refresh")
                .header("Content-Type", "application/json")
                .body("{\"token\":\"" + token + "\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(refresh.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(refresh.getBody());
        assertThat(json.get("token").asText()).isNotBlank();
    }

    @Test
    void refresh_rejectsInvalidToken() {
        // IdentityService.refresh throws ResourceNotFoundException → 404. In
        // Spring 6.1+ RestClient throws on 4xx, so assert the exception status.
        org.springframework.web.client.HttpClientErrorException ex = null;
        try {
            client().post().uri("/api/v1/auth/refresh")
                    .header("Content-Type", "application/json")
                    .body("{\"token\":\"garbage-token\"}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            ex = e;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void verify_returnsValidForGoodToken() throws Exception {
        String token = registerAndGetToken("verify@test.com");

        var verify = client().post().uri("/api/v1/internal/verify")
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
        var verify = client().post().uri("/api/v1/internal/verify")
                .header("Content-Type", "application/json")
                .body("{\"token\":\"bogus\"}")
                .retrieve()
                .toEntity(String.class);
        JsonNode json = objectMapper.readTree(verify.getBody());
        assertThat(json.get("valid").asBoolean()).isFalse();
    }
}