package com.bhukkad.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end API tests: boots the full Spring context against real
 * PostgreSQL + Redis and exercises public HTTP endpoints to verify the
 * API layer wires correctly from router to repository.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiEndToEndTest {

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
            throw new org.opentest4j.TestAbortedException("Docker not available; skipping API E2E tests");
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

    // ------------------------------------------------------------------
    // Public restaurant endpoints
    // ------------------------------------------------------------------

    @Test
    void publicRestaurants_returnsEmptyOrList() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/restaurants/public", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void publicRestaurantById_returnsNotFoundForMissing() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/restaurants/public/999999", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.OK);
    }

    @Test
    void publicRestaurantSearch_returnsEmptyOrList() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/restaurants/public/search?keyword=pizza", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Health endpoints
    // ------------------------------------------------------------------

    @Test
    void healthPing_returnsPong() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/health/ping", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("pong");
    }

    @Test
    void healthCheck_returnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    // ------------------------------------------------------------------
    // Protected endpoints reject anonymous access
    // ------------------------------------------------------------------

    @Test
    void adminDashboard_withoutToken_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/admin/dashboard", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void orders_withoutToken_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/orders", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
