package com.bhukkad.identity.support;


import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.opentest4j.TestAbortedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIdentityPostgresTest {

    protected static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:16-alpine").asCompatibleSubstituteFor("postgres");

    // Same ready-log contract as PostgreSQLContainer's default wait strategy,
    // but with a load-tolerant timeout: loaded CI machines (concurrent module
    // builds) push postgres initdb past the 60s default and every retry burns
    // another full context boot.
    /**
     * Shared Redis: identity boots a Redis cache-invalidation listener that
     * fails the context without a live Redis, and the W1-AUTH login-lockout
     * is Redis-backed — a real container keeps both honest.
     */
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("identity")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw")
            .waitingFor(new org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy()
                    .withRegEx(".*database system is ready to accept connections.*\\s")
                    .withTimes(2)
                    .withStartupTimeout(java.time.Duration.ofMinutes(5)));

    /** W1-AUTH: shared mesh secret — internal paths require X-Service-Token. */
    public static final String SERVICE_JWT_SECRET = "service-mesh-secret-0123456789abcdef";
    /** Fixed kid so the harness JWKS server and identity agree on the key id. */
    public static final String TEST_KEY_ID = "w1-test-key";

    /**
     * W1-AUTH (ADR-004): the RSA keypair identity signs with in tests. The PEM
     * is handed to identity via {@code app.jwt.rsa.private-key-pem}; the public
     * half is served by a tiny local JWKS server so identity's own
     * PlatformJwtValidator can verify RS256 Bearer tokens on RANDOM_PORT.
     */
    public static final RSAKey TEST_RSA_KEY;
    static final String TEST_RSA_PEM;
    static final HttpServer JWKS_SERVER;

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Testcontainers integration tests");
        }
        POSTGRES.start();
        REDIS.start();
        try {
            TEST_RSA_KEY = new RSAKeyGenerator(2048).keyID(TEST_KEY_ID).generate();
            TEST_RSA_PEM = toPkcs8Pem((RSAPrivateCrtKey) TEST_RSA_KEY.toPrivateKey());
            JWKS_SERVER = startJwksServer();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot set up W1-AUTH test harness", e);
        }
    }

    private static HttpServer startJwksServer() throws Exception {
        String jwksJson = new JWKSet(TEST_RSA_KEY.toPublicJWK()).toString();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String toPkcs8Pem(RSAPrivateCrtKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration-pg");
        // PERF-0 raises the default pool (minimum-idle 20); several cached
        // contexts share one Testcontainers PG (max_connections 100) inside a
        // single suite, so cap the test pool to keep the container solvent.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        // Shared Redis for cache invalidation + login lockout. Generous
        // timeouts: the prod yml's 1-2s handshake budget is too tight on a
        // loaded CI box running concurrent module builds.
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "15000");
        registry.add("spring.data.redis.connect-timeout", () -> "15000");
        registry.add("app.jwt.secret", () -> "0123456789abcdef0123456789abcdef");
        registry.add("app.jwt.ttl-minutes", () -> "60");
        // W1-AUTH: RS256 cutover — identity signs with the harness keypair.
        registry.add("app.jwt.rsa.private-key-pem", () -> TEST_RSA_PEM);
        registry.add("app.jwt.rsa.key-id", () -> TEST_KEY_ID);
        // PlatformJwtValidator (identity's own filter): verify RS256 via the
        // harness JWKS server; HMAC grace covers legacy HS256 in other tests.
        registry.add("app.auth.jwt.jwks-url",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
        // Service mesh secret: /api/v1/internal/** now requires X-Service-Token.
        registry.add("app.auth.service.jwt-secret", () -> SERVICE_JWT_SECRET);
    }
}
