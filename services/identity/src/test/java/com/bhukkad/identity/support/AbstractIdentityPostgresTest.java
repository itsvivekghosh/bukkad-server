package com.bhukkad.identity.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Testcontainers + test-crypto base for every identity integration test.
 *
 * <p>Supplies, for the whole test JVM (static fields, started once and reused
 * by every subclass):
 * <ul>
 *   <li><b>PostgreSQL</b> — {@code spring.datasource.*}. Required because the
 *       repository tests run with
 *       {@code @AutoConfigureTestDatabase(replace = NONE)} so the real Flyway
 *       migrations execute against it.</li>
 *   <li><b>Redis</b> — {@code spring.data.redis.*}. Needed by the login-lockout
 *       tests, which exercise the real per-(email,IP) counter rather than a
 *       mock. A plain {@link GenericContainer} is used instead of
 *       {@code RedisContainer} so no extra Testcontainers module is required.</li>
 *   <li><b>An RSA key pair</b> — the application under test is pointed at
 *       {@link #TEST_RSA_KEY}'s PEM via {@code app.auth.jwt.rsa.*}, and the
 *       tests sign their tokens with the very same key. That is what makes the
 *       JWKS assertions meaningful: the {@code kid} the endpoint publishes is
 *       {@link #TEST_KEY_ID}, and a token minted in the test verifies against
 *       the key the service actually loaded.</li>
 * </ul>
 *
 * <p>{@code replace = NONE} is declared here (not only on the repository tests)
 * because the {@code @SpringBootTest} subclasses would otherwise make Spring
 * Boot swap the container-backed DataSource for an embedded one — and with only
 * the PostgreSQL driver on the classpath that substitution fails outright with
 * "Failed to replace DataSource with an embedded database for tests".
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractIdentityPostgresTest {

    /** {@code kid} published by the JWKS endpoint under test. */
    protected static final String TEST_KEY_ID = "test-key-1";

    /** HMAC secret for service-to-service (mesh) tokens; HS256 needs >= 32 bytes. */
    protected static final String SERVICE_JWT_SECRET = "test-service-jwt-secret-0123456789abcdef";

    /** The RSA key the service under test signs with, and the tests verify against. */
    protected static final RSAKey TEST_RSA_KEY = generateRsaKey();

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("identity")
                    .withUsername("app")
                    .withPassword("app_pass");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /**
     * Standalone JWKS endpoint.
     *
     * <p>The platform validator fetches keys over HTTP from
     * {@code app.auth.jwt.jwks-url}. These tests boot identity on a
     * RANDOM_PORT, so the service cannot validate its own tokens through
     * {@code http://localhost:8081} — every bearer token came back 401 and the
     * {@code kid} on the endpoint did not match. Serving the public JWK from a
     * small in-process HTTP server on an ephemeral port gives the validator a
     * stable URL whose key and {@code kid} are exactly {@link #TEST_RSA_KEY}
     * and {@link #TEST_KEY_ID}, so tokens minted by {@code jwtService.issue(..)}
     * and the JWKS the service publishes are provably the same key.
     */
    private static final JwksTestServer JWKS_SERVER = JwksTestServer.start(TEST_RSA_KEY, TEST_KEY_ID);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private static RSAKey generateRsaKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(TEST_KEY_ID)
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate test RSA key", e);
        }
    }

    /** PKCS#8 PEM of {@link #TEST_RSA_KEY}, the shape {@code private-key-pem} expects. */
    private static String privateKeyPem() {
        try {
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            String base64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                    .encodeToString(((RSAPrivateKey) TEST_RSA_KEY.toRSAPrivateKey()).getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode test RSA private key", e);
        }
    }
    /** Public half of {@link #TEST_RSA_KEY}, handy for tests that build a JWKS themselves. */
    protected static RSAKey publicJwk() {
        try {
            return new RSAKey.Builder(TEST_RSA_KEY.toRSAPublicKey())
                    .keyID(TEST_KEY_ID)
                    .build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("failed to derive test RSA public key", e);
        }
    }

    /** The full key set as the JWKS endpoint would serve it. */
    protected static JWKSet jwkSet() {
        return new JWKSet(publicJwk());
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());

        // Point the service's RS256 signer at the same key the tests use.
        // NOTE the prefixes: RsaKeyProperties binds to "app.jwt.rsa" (identity's
        // own signer), while the platform-lib validators bind to
        // "app.auth.jwt" / "app.auth.service". Using the wrong prefix here is
        // silent — the service just auto-generates its own key and the JWKS
        // assertions fail on a kid mismatch.
        registry.add("app.jwt.rsa.private-key-pem", AbstractIdentityPostgresTest::privateKeyPem);
        registry.add("app.jwt.rsa.key-id", () -> TEST_KEY_ID);
        registry.add("app.auth.jwt.secret", () -> SERVICE_JWT_SECRET);
        registry.add("app.auth.service.jwt-secret", () -> SERVICE_JWT_SECRET);

        // Validate the service's OWN RS256 tokens. The yml default points at
        // http://localhost:8081/.well-known/jwks.json, but these tests boot on a
        // RANDOM_PORT, so that fetch fails and every bearer token is rejected
        // with 401. The real port is captured by PortCapture (below) and read
        // lazily — the JWKS is only fetched on the first token validation, by
        // which time the web server has started.
        registry.add("app.auth.jwt.jwks-url", () -> JWKS_SERVER.baseUrl() + "/.well-known/jwks.json");
    }


    /** Unused directly, but keeps the RSAPublicKey import meaningful for subclasses. */
    protected static Class<?> publicKeyType() {
        return RSAPublicKey.class;
    }
}
