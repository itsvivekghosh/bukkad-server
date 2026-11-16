package com.bhukkad.common.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dependency-chaos integration test using ToxiProxy
 * (https://github.com/Shopify/toxiproxy) fronting PostgreSQL and Redis via
 * Testcontainers {@link GenericContainer}.
 *
 * <p>ToxiProxy injects latency and connection faults between the test client
 * and each dependency, emulating the {@code redis-latency-100ms} and
 * {@code db-connection-exhaustion} Chaos Mesh experiments at the test layer.
 * Every toxic has a bounded blast radius (one dependency) and is removed
 * (rolled back) in {@link #afterAll} so no fault persists past the test.</p>
 *
 * <p>Skips cleanly when Docker is unavailable, matching the platform-lib
 * Testcontainers convention.</p>
 *
 * <p>Verification notes:</p>
 * <ul>
 *   <li>{@code testcontainers-postgresql} 1.20.4 resolves locally (jar present).</li>
 *   <li>{@code testcontainers-redis} does NOT resolve locally (only a
 *       {@code .lastUpdated} marker), so Redis is bootstrapped with
 *       {@link GenericContainer} + the official {@code redis:7-alpine} image
 *       instead of {@code RedisContainer}.</li>
 *   <li>The {@code toxiproxy} testcontainers module is not available offline,
 *       so ToxiProxy itself is run as a {@link GenericContainer} backed by the
 *       official {@code toxiproxy/toxiproxy:latest} image and driven through
 *       its HTTP control API.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ToxiProxyDependencyChaosTest {

    private static final DockerImageName TOXIPROXY_IMAGE =
            DockerImageName.parse("toxiproxy/toxiproxy:latest");

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("bhukkad_test")
                    .withUsername("bhukkad")
                    .withPassword("bhukkad_test_pw");

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static final GenericContainer<?> TOXIPROXY =
            new GenericContainer<>(TOXIPROXY_IMAGE)
                    .withExposedPorts(8474, 5432, 6379)
                    // proxies: toxiproxy:5432 -> postgres:5432 ; toxiproxy:6379 -> redis:6379
                    .withCommand("-port", "8474");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        if (!org.testcontainers.containers.DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping ToxiProxy dependency-chaos test");
        }
        POSTGRES.start();
        REDIS.start();
        TOXIPROXY.start();
    }

    @BeforeAll
    static void configureProxies() throws Exception {
        // ToxiProxy control plane address.
        final String api = "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8474);

        // Proxy postgres traffic through toxiproxy:5432 -> postgres container:5432
        post(api + "/proxies", Map.of(
                "name", "postgres",
                "listen", "0.0.0.0:5432",
                "upstream", POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)));

        // Proxy redis traffic through toxiproxy:6379 -> redis container:6379
        post(api + "/proxies", Map.of(
                "name", "redis",
                "listen", "0.0.0.0:6379",
                "upstream", REDIS.getHost() + ":" + REDIS.getMappedPort(6379)));
    }

    @Test
    void postgresLatencyToxicSlowsQueries() throws Exception {
        final String api = "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8474);

        // Inject 100ms latency on the postgres dependency (single-dependency blast radius).
        post(api + "/proxies/postgres/toxics", Map.of(
                "name", "postgres-latency",
                "type", "latency",
                "stream", "downstream",
                "toxicity", 1.0,
                "attributes", Map.of("latency", 100)));

        try {
            long before = timedQueryMillis(); // baseline through toxiproxy (should already be slow)
            long after = timedQueryMillis();
            // Both reads traverse the same toxic; the second confirms the fault persists
            // for the whole window (blast radius bounded to this one dependency).
            if (after < 100) {
                throw new AssertionError("Expected >=100ms latency through ToxiProxy, got " + after + "ms");
            }
        } finally {
            // Rollback: remove the toxic so the dependency recovers (automatic rollback).
            delete(api + "/proxies/postgres/toxics/postgres-latency");
        }

        // Post-rollback: queries should be fast again (connection healthy).
        long recovered = timedQueryMillis();
        if (recovered > 200) {
            throw new AssertionError("PostgreSQL did not recover after toxic removal: " + recovered + "ms");
        }
    }

    @Test
    void redisTimeoutToxicFailsClosed() throws Exception {
        final String api = "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8474);

        // Inject a reset/timeout toxic on the redis dependency (single-dependency blast radius).
        post(api + "/proxies/redis/toxics", Map.of(
                "name", "redis-reset",
                "type", "reset",
                "stream", "downstream",
                "toxicity", 1.0));

        try {
            // The fault must be fail-closed: connecting through ToxiProxy to Redis fails.
            boolean connected = tryTcpConnect(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(6379), Duration.ofSeconds(3));
            if (connected) {
                throw new AssertionError("Expected redis connection to be refused/reset through ToxiProxy (fail-closed)");
            }
        } finally {
            // Rollback: remove the toxic so Redis recovers.
            delete(api + "/proxies/redis/toxics/redis-reset");
        }

        // Post-rollback: Redis should accept connections again.
        boolean recovered = tryTcpConnect(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(6379), Duration.ofSeconds(3));
        if (!recovered) {
            throw new AssertionError("Redis did not recover after toxic removal");
        }
    }

    @AfterAll
    static void afterAll() {
        // Rollback: stop containers (no fault persists past the test).
        try { TOXIPROXY.stop(); } catch (Exception ignored) {}
        try { REDIS.stop(); } catch (Exception ignored) {}
        try { POSTGRES.stop(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void post(String url, Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("ToxiProxy POST " + url + " failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    private static void delete(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .DELETE()
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300 && resp.statusCode() != 404) {
            throw new IOException("ToxiProxy DELETE " + url + " failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    private long timedQueryMillis() throws SQLException {
        Instant start = Instant.now();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl().replace(POSTGRES.getHost(), TOXIPROXY.getHost())
                        .replace(String.valueOf(POSTGRES.getMappedPort(5432)),
                                String.valueOf(TOXIPROXY.getMappedPort(5432))),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            conn.createStatement().executeQuery("SELECT 1");
        }
        return Duration.between(start, Instant.now()).toMillis();
    }

    private boolean tryTcpConnect(String host, int port, Duration timeout) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
