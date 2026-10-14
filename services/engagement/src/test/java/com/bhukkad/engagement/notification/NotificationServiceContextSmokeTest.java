package com.bhukkad.engagement.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceContextSmokeTest extends AbstractNotificationPostgresTest {
    @LocalServerPort private int port;

    @Test
    void healthEndpointResponds() {
        // onStatus no-op: this Testcontainers slice provisions Postgres only, so the
        // aggregate health status is DOWN (redis absent) and retrieve() would
        // throw HttpServerErrorException on the 503 before any assertion runs.
        String health = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, (request, response) -> { })
                .body(String.class);
        assertThat(health).contains("\"db\":{\"status\":\"UP\"");
    }
}
