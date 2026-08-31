package com.bhukkad.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServiceContextSmokeTest extends AbstractOrderPostgresTest {

    @LocalServerPort private int port;

    @Autowired private com.bhukkad.order.domain.OrderRepository orderRepository;

    @Test
    void healthEndpointResponds() {
        String health = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(health).contains("\"status\":\"UP\"");
    }

    @Test
    void repositoryIsWired() {
        assertThat(orderRepository).isNotNull();
    }
}