package com.bhukkad.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.stream.Stream;

/**
 * Base integration test class providing common test infrastructure.
 * Includes Testcontainers, MockMvc, ObjectMapper, and transactional test support.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected ObjectMapper objectMapper;

	@PersistenceContext
	protected EntityManager entityManager;

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", TestcontainersConfiguration::getPostgresJdbcUrl);
		registry.add("spring.datasource.username", TestcontainersConfiguration::getPostgresJdbcUrl);
		registry.add("spring.datasource.password", TestcontainersConfiguration::getPostgresJdbcUrl);
		registry.add("spring.kafka.bootstrap-servers", TestcontainersConfiguration::getKafkaBootstrapServers);
		registry.add("spring.redis.host", TestcontainersConfiguration::getRedisHost);
		registry.add("spring.redis.port", () -> String.valueOf(TestcontainersConfiguration.getRedisPort()));
	}

	{
		objectMapper.registerModule(new JavaTimeModule());
	}

	/**
	 * Perform a GET request and return the result actions for assertions.
	 */
	protected ResultActions get(String url) throws Exception {
		return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.accept(org.springframework.http.MediaType.APPLICATION_JSON));
	}

	/**
	 * Perform a POST request with a body and return the result actions.
	 */
	protected ResultActions post(String url, Object body) throws Exception {
		return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.accept(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	/**
	 * Perform a PUT request with a body and return the result actions.
	 */
	protected ResultActions put(String url, Object body) throws Exception {
		return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.accept(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	/**
	 * Perform a PATCH request with a body and return the result actions.
	 */
	protected ResultActions patch(String url, Object body) throws Exception {
		return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.accept(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	/**
	 * Perform a DELETE request and return the result actions.
	 */
	protected ResultActions delete(String url) throws Exception {
		return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.accept(org.springframework.http.MediaType.APPLICATION_JSON));
	}

	/**
	 * Add JWT authorization header to request.
	 */
	protected RequestPostProcessor withBearerToken(String token) {
		return request -> {
			request.addHeader("Authorization", "Bearer " + token);
			return request;
		};
	}

	/**
	 * Add service-to-service authorization header.
	 */
	protected RequestPostProcessor withServiceToken() {
		return request -> {
			request.addHeader("X-Service-Token", "test-service-token");
			return request;
		};
	}

	/**
	 * Flush and clear the JPA persistence context.
	 */
	protected void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	/**
	 * Execute a native SQL query.
	 */
	protected void executeSql(String sql) {
		entityManager.createNativeQuery(sql).executeUpdate();
	}

	/**
	 * Assert that a list has the expected size.
	 */
	protected <T> void assertListSize(List<T> list, int expectedSize) {
		org.assertj.core.api.Assertions.assertThat(list).hasSize(expectedSize);
	}

	/**
	 * Parse JSON response to a type.
	 */
	protected <T> T parseResponse(ResultActions resultActions, Class<T> type) throws Exception {
		String json = resultActions.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(json, type);
	}

	/**
	 * Parse JSON response to a generic type.
	 */
	protected <T> T parseResponse(ResultActions resultActions, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) throws Exception {
		String json = resultActions.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(json, typeRef);
	}
}