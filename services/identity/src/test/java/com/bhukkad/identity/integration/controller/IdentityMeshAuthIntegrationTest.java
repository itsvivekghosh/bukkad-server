package com.bhukkad.identity.integration.controller;

import com.bhukkad.identity.support.AbstractIdentityPostgresTest;

import com.bhukkad.identity.support.AbstractIdentityPostgresTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1-AUTH deliverable 5: reject-absent-token 401 matrix on /api/v1/internal/**
 * — no header, garbage, wrong secret, disallowed subject, and the happy path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdentityMeshAuthIntegrationTest extends AbstractIdentityPostgresTest {

    @LocalServerPort
    private int port;

    private RestClient rawClient() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static String meshToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SERVICE_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static String meshTokenWithOtherSecret(String subject) {
        String otherSecret = "another-mesh-secret-0123456789abcdefghij";
        return Jwts.builder()
                .subject(subject)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(otherSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private int postInternal(String token) {
        RestClient.RequestBodySpec spec = rawClient().post()
                .uri("/api/v1/internal/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"token\":\"x\"}");
        if (token != null) {
            spec = spec.header("X-Service-Token", token);
        }
        try {
            return spec.retrieve().toEntity(String.class).getStatusCode().value();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void internalPath_withoutToken_401() {
        assertThat(postInternal(null)).isEqualTo(401);
    }

    @Test
    void internalPath_withGarbageToken_401() {
        assertThat(postInternal("garbage.token.value")).isEqualTo(401);
    }

    @Test
    void internalPath_withWrongSecretToken_401() {
        assertThat(postInternal(meshTokenWithOtherSecret("order"))).isEqualTo(401);
    }

    @Test
    void internalPath_withDisallowedSubject_403() {
        assertThat(postInternal(meshToken("rogue-service"))).isEqualTo(403);
    }

    @Test
    void internalPath_withValidMeshToken_200() {
        assertThat(postInternal(meshToken("identity"))).isEqualTo(200);
    }

    @Test
    void internalAdminSurface_withoutToken_401() {
        try {
            rawClient().get().uri("/api/v1/internal/admin/users").retrieve().toEntity(String.class);
            throw new AssertionError("expected 401");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }

    @Test
    void internalAdminSurface_withValidMeshToken_200() {
        var response = rawClient().get()
                .uri("/api/v1/internal/admin/users")
                .header("X-Service-Token", meshToken("gateway"))
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"items\"");
    }
}
