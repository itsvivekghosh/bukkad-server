package com.bhukkad.engagement.growth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for growth's PostgreSQL integration tests.
 * Connects to the existing PostgreSQL instance on port 5432 instead of starting Testcontainers.
 */
public abstract class AbstractGrowthPostgresTest {

    static {
        // Connect to existing PostgreSQL on port 5432 instead of starting Testcontainers
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/engagement");
        registry.add("spring.datasource.username", () -> "app");
        registry.add("spring.datasource.password", () -> "app_pass");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("app.events.external.enabled", () -> "false");
    }
}