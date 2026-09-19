package com.bhukkad.social;

import com.bhukkad.common.outbox.OutboxEventRepository;
import org.opentest4j.TestAbortedException;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for social service integration tests.
 *
 * <p>Provides Testcontainers for PostgreSQL with PostGIS, Redis, and Redpanda
 * (Kafka-compatible) so integration tests can run without external dependencies.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnableJpaRepositories(basePackages = {
        "com.bhukkad.social.domain.repository",
        "com.bhukkad.common.outbox",
        "com.bhukkad.common.idempotency"
})
@EntityScan(basePackages = {
        "com.bhukkad.social.domain.entity",
        "com.bhukkad.common.outbox",
        "com.bhukkad.common.idempotency"
})
public abstract class AbstractSocialIntegrationTest {

    protected static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgis/postgis:15-3.3").asCompatibleSubstituteFor("postgres");

    protected static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7-alpine").asCompatibleSubstituteFor("redis");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("social")
            .withUsername("bhukkad")
            .withPassword("bhukkad_test_pw")
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    // Kafka can be added later if integration tests require event publishing
    // static final KafkaContainer<?> KAFKA = new KafkaContainer<>(...)

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; skipping Testcontainers integration tests");
        }
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void socialTestProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL with PostGIS
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration-pg");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");

        // Redis
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");

        // Kafka can be added later if integration tests require event publishing
        // registry.add("app.events.external.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // registry.add("app.events.external.kafka.consumer-group", () -> "social-test-consumer");
        // registry.add("app.events.external.kafka.platform-topic", () -> "social.events.v1");
        // registry.add("app.events.external.kafka.dlq-topic", () -> "social.events.v1.dlt");
        // registry.add("app.events.external.enabled", () -> "false");
        // registry.add("app.events.external.type", () -> "kafka");

        // Disable tracing for tests
        registry.add("management.tracing.sampling.probability", () -> "0.0");
        registry.add("management.zipkin.tracing.endpoint", () -> "http://localhost:9411/api/v2/spans");
    }
}
