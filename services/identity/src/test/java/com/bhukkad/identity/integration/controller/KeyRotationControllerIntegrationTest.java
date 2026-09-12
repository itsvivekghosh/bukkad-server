package com.bhukkad.identity.integration.controller;

import com.bhukkad.identity.support.AbstractIdentityPostgresTest;

import com.bhukkad.identity.support.AbstractIdentityPostgresTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KeyRotationControllerIntegrationTest extends AbstractIdentityPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.bhukkad.identity.config.JwtService jwtService;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private String adminToken() throws Exception {
        String token = jwtService.issue(1L, "admin@bhukkad.in", "ADMIN");
        return token;
    }

    @Test
    void status_returnsKeyMetadataWithoutSecrets() throws Exception {
        String token = adminToken();
        var response = client().get().uri("/api/v1/admin/jwt/status")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("keyId")).isEqualTo(AbstractIdentityPostgresTest.TEST_KEY_ID);
        assertThat(body.get("algorithm")).isEqualTo("RS256");
        assertThat(body.get("jwksEndpoint")).isNotNull();
        assertThat(body).doesNotContainKeys("privateKey", "secret", "pem", "d", "p", "q");
    }

    @Test
    void status_requiresAdminRole() throws Exception {
        String customerToken = jwtService.issue(2L, "user@bhukkad.in", "CUSTOMER");
        org.springframework.web.client.HttpClientErrorException ex = null;
        try {
            client().get().uri("/api/v1/admin/jwt/status")
                    .header("Authorization", "Bearer " + customerToken)
                    .retrieve()
                    .toEntity(Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            ex = e;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void refresh_returns501WhenHmacOnly() throws Exception {
        // In the test profile, jwksUrl IS configured, so we verify the success
        // path. The HMAC-only 501 path is exercised by the unit test in
        // PlatformJwtValidatorTest where jwksUrl is null.
        String token = adminToken();
        var response = client().post().uri("/api/v1/admin/jwt/refresh")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("refreshed")).isEqualTo(true);
        assertThat(body.get("timestamp")).isNotNull();
    }
}
