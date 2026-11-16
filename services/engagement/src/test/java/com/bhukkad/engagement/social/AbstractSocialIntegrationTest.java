package com.bhukkad.engagement.social;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for social service integration tests.
 * Connects to the existing PostgreSQL instance on port 5432 instead of starting Testcontainers.
 */
public abstract class AbstractSocialIntegrationTest {

    static {
        // Connect to existing PostgreSQL on port 5432 instead of starting Testcontainers
    }

    @DynamicPropertySource
    static void socialTestProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL on port 5432
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/engagement");
        registry.add("spring.datasource.username", () -> "app");
        registry.add("spring.datasource.password", () -> "app_pass");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");

        // Redis not needed for integration tests
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.data.redis.password", () -> "");

        // Kafka disabled for integration tests
        registry.add("app.events.external.enabled", () -> "false");

        // Disable tracing for tests
        registry.add("management.tracing.sampling.probability", () -> "0.0");
        registry.add("management.zipkin.tracing.endpoint", () -> "http://localhost:9411/api/v2/spans");
    }
}