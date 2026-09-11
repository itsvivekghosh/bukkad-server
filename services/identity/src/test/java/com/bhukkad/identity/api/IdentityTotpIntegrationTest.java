package com.bhukkad.identity.api;

import com.bhukkad.common.util.TOTPGenerator;
import com.bhukkad.identity.AbstractIdentityPostgresTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1-AUTH deliverable 4: TOTP enroll → confirm → verify-on-login. The
 * otpauth:// URI is returned once at enrollment; after confirmation login
 * requires a live code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdentityTotpIntegrationTest extends AbstractIdentityPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private record Session(String token, String email) {
    }

    private Session register() throws Exception {
        String email = "totp_" + System.nanoTime() + "@b.com";
        var register = client().post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body("{\"email\":\"" + email + "\",\"phoneNumber\":\"999\","
                        + "\"fullName\":\"TOTP IT\",\"password\":\"password123\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(register.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(register.getBody());
        return new Session(json.get("token").asText(), email);
    }

    private String enroll(String token) throws Exception {
        var response = client().post().uri("/api/v1/auth/totp/enroll")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(response.getBody());
        String otpauth = json.get("otpauthUri").asText();
        assertThat(otpauth).startsWith("otpauth://totp/");
        assertThat(otpauth).contains("secret=");
        return otpauth;
    }

    private static String secretFromUri(String otpauth) {
        return java.util.Arrays.stream(otpauth.split("[?&]"))
                .filter(part -> part.startsWith("secret="))
                .map(part -> part.substring("secret=".length()))
                .findFirst().orElseThrow();
    }

    private int confirm(String token, String code) {
        try {
            return client().post().uri("/api/v1/auth/totp/confirm")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":\"" + code + "\"}")
                    .retrieve().toEntity(String.class).getStatusCode().value();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return e.getStatusCode().value();
        }
    }

    private int login(String email, String totpCode) {
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123\""
                + (totpCode == null ? "" : ",\"totpCode\":\"" + totpCode + "\"}") + "}";
        try {
            return client().post().uri("/api/v1/auth/login")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().toEntity(String.class).getStatusCode().value();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void enroll_confirmWithLiveCode_thenLoginRequiresCode() throws Exception {
        Session session = register();
        String otpauth = enroll(session.token());
        String secret = secretFromUri(otpauth);

        // Wrong code → 400 (BusinessException).
        assertThat(confirm(session.token(), "000000")).isEqualTo(400);
        // Live code → 200, TOTP enabled.
        String live = TOTPGenerator.generateCode(secret);
        assertThat(confirm(session.token(), live)).isEqualTo(200);

        // Login without a code → 401 TOTP challenge.
        assertThat(login(session.email(), null)).isEqualTo(401);
        // Login with a wrong code → 401.
        assertThat(login(session.email(), "000000")).isEqualTo(401);
        // Login with a live code → 200.
        assertThat(login(session.email(), TOTPGenerator.generateCode(secret))).isEqualTo(200);
    }

    @Test
    void enroll_requiresAuthentication() {
        try {
            client().post().uri("/api/v1/auth/totp/enroll").retrieve().toEntity(String.class);
            throw new AssertionError("expected 401");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }
}
