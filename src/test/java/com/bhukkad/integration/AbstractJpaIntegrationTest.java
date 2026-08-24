package com.bhukkad.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
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
 */
@Testcontainers
public abstract class AbstractJpaIntegrationTest {

    /** MySQL 8.x image matching the production MySQL version family. */
    protected static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0.36").asCompatibleSubstituteFor("mysql");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("bhukkad_test")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw");

    static {
        // Flyway baseline note: our migrations use CREATE TABLE IF NOT EXISTS and
        // information_schema guards, so a fresh database is migrated cleanly on
        // every test run. This proves the migration set is self-consistent.
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
        // ddl-auto is none in production (Flyway owns the schema); keep it that way.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // The read-replica routing must be disabled in the single-container test.
        registry.add("spring.datasource.read-replica.enabled", () -> "false");
    }
}
