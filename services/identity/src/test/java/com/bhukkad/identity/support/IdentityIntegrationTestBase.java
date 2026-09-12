package com.bhukkad.identity.support;


import com.bhukkad.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.stream.Stream;

/**
 * Base integration test configuration for Identity service.
 * Provides Testcontainers setup and common test properties.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IdentityIntegrationTestBase extends TestcontainersConfiguration {

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", TestcontainersConfiguration::getPostgresJdbcUrl);
		registry.add("spring.datasource.username", () -> "test");
		registry.add("spring.datasource.password", () -> "test");
		registry.add("spring.kafka.bootstrap-servers", TestcontainersConfiguration::getKafkaBootstrapServers);
		registry.add("spring.redis.host", TestcontainersConfiguration::getRedisHost);
		registry.add("spring.redis.port", () -> String.valueOf(TestcontainersConfiguration.getRedisPort()));
		registry.add("app.auth.jwt.secret", () -> "test-secret-key-for-testing-purposes-only-32-chars-min");
		registry.add("app.auth.jwt.access-token-ttl", () -> "3600000");
		registry.add("app.auth.jwt.refresh-token-ttl", () -> "86400000");
	}

	/**
	 * Arguments provider for parameterized tests with test data.
	 */
	public static class TestDataArgumentsProvider implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
			return Stream.of(
				Arguments.of("test@example.com", "password123"),
				Arguments.of("user@domain.com", "securePass456"),
				Arguments.of("admin@bhukkad.com", "adminPass789")
			);
		}
	}
}