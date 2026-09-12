package com.bhukkad.testapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.Order;

/**
 * Smoke test for the test-app module.
 * Validates that the Spring Boot application context loads correctly
 * with the microservices reactor on the classpath.
 */
@SpringBootTest(
		classes = TestAppApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class TestAppSmokeTest {

	@Test
	void contextLoads() {
		// If the context loads, the test passes.
	}
}
