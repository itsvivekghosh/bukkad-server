package com.bhukkad.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentServiceContextSmokeTest extends AbstractPaymentPostgresTest {

    @LocalServerPort private int port;

    @Test
    void healthEndpointResponds() {
        String health = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(health).contains("\"status\":\"UP\"");
    }
}
