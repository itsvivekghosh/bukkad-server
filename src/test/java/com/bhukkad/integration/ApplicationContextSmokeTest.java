package com.bhukkad.integration;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context smoke test: boots the ENTIRE Spring application context
 * (controllers, security filter chain, services, repositories, Flyway,
 * scheduled jobs) against real PostgreSQL + Redis containers and verifies the
 * application actually starts and serves health.
 *
 * <p>This is the missing layer in the suite — every other test is either a
 * Mockito unit test or a narrow {@code @DataJpaTest} slice, so nothing
 * previously proved the whole context wires together (bean wiring, filter
 * chain registration, Flyway baseline, scheduler startup).</p>
 *
 * <p>Skips cleanly (TestAbortedException) when Docker is unavailable, matching
 * the repo-wide convention; CI runs it in full.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationContextSmokeTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("bhukkad_test")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw");

    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping full-context smoke test");
        }
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        // The full filter chain and core beans must be wired.
        assertThat(applicationContext.getBean("securityFilterChain")).isNotNull();
        assertThat(applicationContext.getBean("writeDataSource")).isNotNull();
        assertThat(applicationContext.getBean("redisConnectionFactory")).isNotNull();
    }

    @Test
    void healthPing_returnsPong() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/health/ping", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("pong");
    }

    @Test
    void healthCheck_returnsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"UP\"");
    }

    @Test
    void protectedEndpoint_withoutToken_isUnauthorized() {
        // Proves the security filter chain is actually active in the full context.
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/admin/dashboard", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void analyticsExport_withoutToken_isUnauthorized() {
        // Tier 1b: analytics exports stream PII and must NOT be anonymously
        // reachable (previously permitAll). The real security chain must reject
        // unauthenticated requests here.
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/analytics/export/orders", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
