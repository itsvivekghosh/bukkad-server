package com.bhukkad.integration;

import org.opentest4j.TestAbortedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for JPA/repository integration tests that run against a real MySQL
 * instance via Testcontainers.
 *
 * <p>This is intentionally a {@code @DataJpaTest} slice (no web layer, no Kafka,
 * no Redis) so the tests validate the repository JPQL/native queries and the
 * Flyway migration set against a genuine MySQL 8 database — something that is
 * impossible with the mocked unit tests and catches query regressions, schema
 * drift and N+1 mistakes before they reach production.</p>
 *
 * <p>Subclasses only need to declare {@code @DataJpaTest} (and any
 * {@code @Import} of repository-only helpers) and write tests.</p>
 *
 * <p>{@code disabledWithoutDocker = true} keeps the suite green in
 * environments without a Docker daemon (local laptops): the tests SKIP with a
 * clear reason instead of failing with
 * {@code "Could not find a valid Docker environment"}. In CI (GitHub Actions,
 * which provides Docker) they run in full.</p>
 *
 * <p>The container is started manually (not via {@code @Container}) so we can
 * grant the app user server-level CREATE before the Spring context loads:
 * migration V54 creates the per-domain schemas, which requires that privilege.
 * {@code withInitScript} cannot do this — Testcontainers executes init scripts
 * as the configured app user, who cannot GRANT to themselves.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractJpaIntegrationTest {

    /** MySQL 8.x image matching the production MySQL version family. */
    protected static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0").asCompatibleSubstituteFor("mysql");

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("bhukkad_test")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw")
            .withRootPassword("root_test_pw");

    static {
        // Skip (abort) the whole class when Docker is unavailable instead of
        // failing — keeps local laptops and non-Docker environments green.
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Testcontainers integration tests");
        }
        MYSQL.start();
        // V54 (per-domain schemas) requires server-level CREATE. Grant it as
        // MySQL root so a fresh container behaves like production, where
        // operators pre-provision these privileges for the application user.
        // The mysql image leaves root with an empty password on the local
        // socket when MYSQL_ROOT_PASSWORD is unset, so `mysql -uroot` (no -p)
        // authenticates. The SQL is passed via bash -c with double quotes so
        // the shell neither glob-expands `*.*` nor splits on `;`.
        try {
            org.testcontainers.containers.Container.ExecResult result = MYSQL.execInContainer(
                    "bash", "-c",
                    "mysql -uroot -proot_test_pw -e \"GRANT ALL PRIVILEGES ON *.* TO 'bhukkad'@'%'; FLUSH PRIVILEGES;\"");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("GRANT failed: " + result.getStdout() + result.getStderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to grant privileges to test MySQL user", e);
        }
    }

    /**
     * Points the test datasource at the containerised MySQL instead of any
     * embedded/default datasource.
     */
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
}
