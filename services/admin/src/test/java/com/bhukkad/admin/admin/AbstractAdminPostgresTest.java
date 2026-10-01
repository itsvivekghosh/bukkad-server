package com.bhukkad.admin.analytics;

import org.opentest4j.TestAbortedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractAdminPostgresTest {

    /**
     * Slices such as {@code @DataJpaTest} boot without Boot's metrics
     * auto-configuration, yet the context still registers platform-lib
     * instrumentation (OutboxMetrics, EndpointSloMetrics, ...) that injects
     * {@code MeterRegistry}. Those slices therefore failed with
     * "No qualifying bean of type MeterRegistry". Supplying a plain registry
     * keeps the instruments registering, as they do in the real service.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class MetricsTestConfig {
        @org.springframework.context.annotation.Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }

        /**
         * The slice also skips resilience4j auto-configuration while the
         * context still builds platform-lib components that inject it
         * (DynamicHikariPoolConfig, the mesh clients).
         */
        @org.springframework.context.annotation.Bean
        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry() {
            return io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults();
        }
    }

    protected static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:16-alpine").asCompatibleSubstituteFor("postgres");
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("admin").withUsername("bhukkad").withPassword("bhukkad_test_pw");
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
