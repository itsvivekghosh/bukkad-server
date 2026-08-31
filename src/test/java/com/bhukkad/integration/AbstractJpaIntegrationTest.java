package com.bhukkad.integration;

import org.opentest4j.TestAbortedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for JPA/repository integration tests that run against a real
 * PostgreSQL instance via Testcontainers.
 *
 * <p>This is the PostgreSQL counterpart of the former MySQL
 * {@code AbstractJpaIntegrationTest}. It validates the repository JPQL/native
 * queries and the Flyway migration set against genuine PostgreSQL, independent
 * of the old MySQL monolith.
 *
 * <p>{@code disabledWithoutDocker = true} keeps the suite green in
 * environments without a Docker daemon: tests SKIP with a clear reason instead
 * of failing. In CI (which provides Docker) they run in full.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractJpaIntegrationTest {

    /** PostgreSQL 16, the target version family for the migration. */
    protected static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:16-alpine").asCompatibleSubstituteFor("postgres");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("bhukkad_test")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw");

    static {
        // Skip (abort) the whole class when Docker is unavailable instead of
        // failing — keeps local laptops and non-Docker environments green.
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Testcontainers integration tests");
        }
        POSTGRES.start();
    }

    /**
     * Points the test datasource at the containerised PostgreSQL, pins the
     * Hibernate dialect, and restricts Flyway to the PG baseline location so
     * the frozen MySQL migrations never touch a PostgreSQL database.
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration-pg");
    }
}
