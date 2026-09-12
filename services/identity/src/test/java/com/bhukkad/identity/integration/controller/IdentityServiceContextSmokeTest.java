package com.bhukkad.identity.integration.controller;

import com.bhukkad.identity.support.AbstractIdentityPostgresTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context smoke test: boots identity (web + JPA + Flyway + JWT)
 * against PostgreSQL and proves the auth endpoints work end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdentityServiceContextSmokeTest extends AbstractIdentityPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private com.bhukkad.identity.config.JwtService jwtService;

    @Test
    void healthEndpointResponds() {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        String health = client.get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(health).contains("\"status\":\"UP\"");
    }

    @Test
    void jwtIssuesAndVerifies() {
        String token = jwtService.issue(1L, "a@b.com");
        assertThat(jwtService.verifyAndGetCustomerId(token)).isEqualTo(1L);
    }

    @Test
    void registerThenLogin_returnsToken() {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        String registerBody = "{\"email\":\"smoke@b.com\",\"phoneNumber\":\"999\",\"fullName\":\"Smoke\",\"password\":\"password123\"}";
        var registerResponse = client.post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body(registerBody)
                .retrieve()
                .toEntity(String.class);
        assertThat(registerResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(registerResponse.getBody()).contains("\"token\"");

        String loginBody = "{\"email\":\"smoke@b.com\",\"password\":\"password123\"}";
        var loginResponse = client.post().uri("/api/v1/auth/login")
                .header("Content-Type", "application/json")
                .body(loginBody)
                .retrieve()
                .toEntity(String.class);
        assertThat(loginResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(loginResponse.getBody()).contains("\"token\"");
    }

    @Test
    void registerOwnerAndAgent_writesProfilesWithoutUsersDuplicate() {
        // Regression: the JOINED-inheritance entity save() re-inserted into
        // users (users_pkey duplicate) and failed owner/agent registration
        // with a 500. Profile rows must be written natively beside the shared
        // users row instead.
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        long ts = System.currentTimeMillis();

        var ownerResponse = client.post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body("{\"email\":\"owner_regr_" + ts + "@b.com\",\"phoneNumber\":\"9712345601\","
                        + "\"fullName\":\"Owner Regr\",\"password\":\"password123\",\"role\":\"RESTAURANT_OWNER\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(ownerResponse.getStatusCode().value()).isEqualTo(200);

        var agentResponse = client.post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body("{\"email\":\"agent_regr_" + ts + "@b.com\",\"phoneNumber\":\"9612345601\","
                        + "\"fullName\":\"Agent Regr\",\"password\":\"password123\",\"role\":\"DELIVERY_AGENT\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(agentResponse.getStatusCode().value()).isEqualTo(200);
    }
}
