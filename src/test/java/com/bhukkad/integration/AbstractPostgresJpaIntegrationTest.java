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
 * <p>This is the PostgreSQL counterpart of {@link AbstractJpaIntegrationTest}
 * (architecture-microservices-postgresql.md §12 P0 — "PG Testcontainers
 * harness"). It validates the PostgreSQL baseline for the {@code bhukkad-common}
 * tables (outbox, DLQ, saga, idempotency) and the repository JPQL/native
 * queries against genuine PostgreSQL, independent of the MySQL monolith's
 * frozen V1..V64 migration set.</p>
 *
 * <p>The MySQL migrations in {@code classpath:db/migration} are never applied
 * here: {@code spring.flyway.locations} is re-pointed at the PG baseline
 * ({@code classpath:db/migration-pg}) via {@link @DynamicPropertySource} so
 * Flyway only sees PostgreSQL DDL on a fresh PostgreSQL database.</p>
 *
 * <p>{@code disabledWithoutDocker = true} keeps the suite green in
 * environments without a Docker daemon: tests SKIP with a clear reason instead
 * of failing. In CI (which provides Docker) they run in full.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresJpaIntegrationTest {

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
