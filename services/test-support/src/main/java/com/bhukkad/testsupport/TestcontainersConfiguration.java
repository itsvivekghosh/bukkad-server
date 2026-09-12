package com.bhukkad.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base Testcontainers configuration for all integration tests.
 * Provides shared PostgreSQL, Kafka, and Redis containers.
 */
@Testcontainers
public abstract class TestcontainersConfiguration {

	@Container
	protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
		.withDatabaseName("testdb")
		.withUsername("test")
		.withPassword("test")
		.withInitScript("db/init-test-db.sql");

	@Container
	protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

	@Container
	protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
		.withExposedPorts(6379);

	static {
		// Configure system properties for Spring Boot auto-configuration
		POSTGRES.start();
		KAFKA.start();
		REDIS.start();

		System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
		System.setProperty("spring.datasource.username", POSTGRES.getUsername());
		System.setProperty("spring.datasource.password", POSTGRES.getPassword());
		System.setProperty("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
		System.setProperty("spring.redis.host", REDIS.getHost());
		System.setProperty("spring.redis.port", REDIS.getMappedPort(6379).toString());
	}

	/**
	 * Get the PostgreSQL JDBC URL.
	 */
	public static String getPostgresJdbcUrl() {
		return POSTGRES.getJdbcUrl();
	}

	/**
	 * Get the Kafka bootstrap servers.
	 */
	public static String getKafkaBootstrapServers() {
		return KAFKA.getBootstrapServers();
	}

	/**
	 * Get the Redis host.
	 */
	public static String getRedisHost() {
		return REDIS.getHost();
	}

	/**
	 * Get the Redis port.
	 */
	public static int getRedisPort() {
		return REDIS.getMappedPort(6379);
	}
}