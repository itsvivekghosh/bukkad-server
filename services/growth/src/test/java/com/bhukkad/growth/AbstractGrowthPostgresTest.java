package com.bhukkad.growth;

import org.opentest4j.TestAbortedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for growth's PostgreSQL integration tests (mirrors
 * {@code AbstractIdentityPostgresTest} — growth has no prior harness).
 *
 * <p>Boots {@code postgres:16-alpine}, points the datasource at it, pins the
 * dialect, and restricts Flyway to the growth baseline + service migrations
 * ({@code classpath:db/migration-pg}). Redis stays unstubbed at the
 * container level: loyalty cache calls fail soft (write-through and reads
 * degrade to the ledger), which is exactly the production contract under a
 * Redis outage.</p>
 *
 * <p>{@code disabledWithoutDocker = true} keeps the suite green where Docker
 * is unavailable: tests SKIP with a clear reason instead of failing.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractGrowthPostgresTest {

    protected static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:16-alpine").asCompatibleSubstituteFor("postgres");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("growth")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw");

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Testcontainers integration tests");
        }
        POSTGRES.start();
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
    }
}
