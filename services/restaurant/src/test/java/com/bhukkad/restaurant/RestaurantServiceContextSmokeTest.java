package com.bhukkad.restaurant;

import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context smoke test: boots the entire restaurant (web + JPA +
 * Flyway + platform beans) against a real PostgreSQL container and proves the
 * actuator health endpoint responds. This is the P2 exit-gate check that the
 * service is a deployable unit, not just a library.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RestaurantServiceContextSmokeTest extends AbstractRestaurantPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Test
    void contextBootsAndHealthEndpointResponds() {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        String health = client.get().uri("/actuator/health").retrieve().body(String.class);

        assertThat(health).contains("\"status\":\"UP\"");
    }

    @Test
    void platformAndDomainRepositoriesAreWired() {
        assertThat(outboxEventRepository).isNotNull();
        assertThat(restaurantRepository).isNotNull();
    }
}
