package com.bhukkad.admin.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.endpoint.health.show-details=always")
class AdminServiceContextSmokeTest extends AbstractAdminPostgresTest {
    @LocalServerPort private int port;

    @Test
    void healthEndpointResponds() {
        // This Testcontainers slice provisions Postgres only — Redis is absent
        // (no broker in CI either), which drags the AGGREGATE status to DOWN
        // and made the whole-body "status":"UP" assertion environment-dependent.
        // Show component details and assert the provisioned dependency: the db
        // component must be UP. onStatus no-op keeps the 5xx path non-throwing.
        String health = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, (request, response) -> { })
                .body(String.class);
        assertThat(health).contains("\"db\":{\"status\":\"UP\"");
    }
}
