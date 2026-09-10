package com.bhukkad.growth.loyalty;

import com.bhukkad.growth.AbstractGrowthPostgresTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the credit surface requires the platform service-JWT (ADR-005 /
 * audit feature #4): the {@code ServiceJwtAuthFilter} is wired into growth's
 * chain and the {@code @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")}
 * gate means an anonymous caller gets 401, a validated mesh service passes
 * authorization (it reaches the parameter/idempotency layer, not the security
 * layer), and a credit executes idempotently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.service.jwt-secret=0123456789abcdef0123456789abcdef",
                "app.auth.service.allowed-services=order,identity,growth"
        })
class GrowthCreditEndpointSecurityPostgresTest extends AbstractGrowthPostgresTest {

    private static final SecretKey KEY =
            Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient client;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM loyalty_points_ledger");
        jdbcTemplate.update("DELETE FROM loyalty_point_balances");
        jdbcTemplate.update("DELETE FROM idempotency_records");
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousCaller_rejected401() {
        Integer status = client.post()
                .uri("/api/v1/customers/9001/loyalty/credit?points=10&reason=test")
                .header("Idempotency-Key", "anon-1")
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(401);
    }

    @Test
    void serviceToken_passesAuthorization_missingIdempotencyKeyIs400() {
        Integer status = client.post()
                .uri("/api/v1/customers/9002/loyalty/credit?points=10&reason=test")
                .header("X-Service-Token", serviceToken("order"))
                .exchange((req, res) -> res.getStatusCode().value());
        // 400 (missing Idempotency-Key header), NOT 401/403 — i.e. the caller
        // passed both the service filter and the @PreAuthorize gate.
        assertThat(status).isEqualTo(400);
    }

    @Test
    void serviceToken_withIdempotencyKey_creditsOnce_replayIsNoop() {
        String key = "svc-order:order:42";
        var first = client.post()
                .uri("/api/v1/customers/9003/loyalty/credit?points=250&reason=ORDER_REWARD")
                .header("X-Service-Token", serviceToken("order"))
                .header("Idempotency-Key", key)
                .retrieve()
                .toBodilessEntity();
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        // Replay: same key, same grant — must not double-credit.
        client.post()
                .uri("/api/v1/customers/9003/loyalty/credit?points=250&reason=ORDER_REWARD")
                .header("X-Service-Token", serviceToken("order"))
                .header("Idempotency-Key", key)
                .retrieve()
                .toBodilessEntity();

        Integer balance = jdbcTemplate.queryForObject(
                "SELECT points FROM loyalty_point_balances WHERE customer_id = 9003", Integer.class);
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loyalty_points_ledger WHERE customer_id = 9003", Integer.class);
        assertThat(balance).isEqualTo(250);
        assertThat(ledgerRows).isEqualTo(1);
    }

    private String serviceToken(String serviceName) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(serviceName)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(300)))
                .claim("service_id", serviceName)
                .signWith(KEY)
                .compact();
    }
}
