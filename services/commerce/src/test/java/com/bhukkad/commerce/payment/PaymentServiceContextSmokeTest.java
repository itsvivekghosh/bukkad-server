package com.bhukkad.commerce.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentServiceContextSmokeTest extends AbstractPaymentPostgresTest {

    @LocalServerPort private int port;

    @Test
    /**
     * Proves the Spring context boots and serves requests over real HTTP.
     *
     * <p>Deliberately asserts the datasource component rather than the
     * aggregate "status". Aggregate health also folds in Redis (and Kafka),
     * which this Testcontainers slice does not provision, so requiring
     * "status":"UP" made the test pass on a developer laptop and fail in CI
     * for reasons unrelated to what a context smoke test is for. Postgres is
     * supplied by the base class, so db=UP is the meaningful signal here;
     * full-stack readiness is covered by scripts/curl-e2e-tests.sh against the
     * composed stack.</p>
     */
    void healthEndpointResponds() {
        // onStatus no-op: the aggregate /actuator/health status is DOWN in this
        // slice (redis/kafka are not provisioned) and retrieve() would throw
        // HttpServerErrorException on the 503 before any assertion runs. The
        // body is what the test inspects, not the aggregate status.
        String health = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, (request, response) -> { })
                .body(String.class);
        assertThat(health).isNotNull();
        // Only the db component is provisioned by this Testcontainers slice: assert
        // IT is UP. Redis/Kafka are intentionally absent, and their nested
        // "status":"DOWN" must not fail a context smoke test (they made the
        // old whole-body doesNotContain assertion env-dependent: green on a
        // laptop with a local Redis, red in CI).
        assertThat(health).contains("\"db\"");
        assertThat(health).contains("\"db\":{\"status\":\"UP\"");
    }
}
