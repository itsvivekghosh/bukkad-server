package com.bhukkad.notification;

import org.opentest4j.TestAbortedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractNotificationPostgresTest {
    protected static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:16-alpine").asCompatibleSubstituteFor("postgres");
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("bhukkad_notification").withUsername("bhukkad").withPassword("bhukkad_test_pw");
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
    }
}
