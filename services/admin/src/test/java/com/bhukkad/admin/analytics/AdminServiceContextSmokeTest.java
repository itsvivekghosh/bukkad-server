package com.bhukkad.admin.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminServiceContextSmokeTest extends AbstractAdminPostgresTest {
    @LocalServerPort private int port;

    @Test
    void healthEndpointResponds() {
        // onStatus no-op keeps the 5xx path non-throwing for robustness, but
        // this service's health exposes no component details ({}→{"status":"UP"}),
        // so the aggregate status is the signal — it is UP exactly when every
        // registered contributor (db) is healthy.
        String health = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, (request, response) -> { })
                .body(String.class);
        assertThat(health).contains("\"status\":\"UP\"");
    }
}
