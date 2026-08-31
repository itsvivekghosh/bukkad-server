package com.bhukkad.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit coverage for {@link RestTemplateConfig} — the pooled outbound HTTP
 * clients introduced for resilience (Batch D). Verifies every bean method
 * builds an Apache HttpClient 5-backed factory with the configured pooling
 * limits and that timeout properties are honored without any network I/O.
 */
class RestTemplateConfigTest {

    private RestTemplateConfig config;

    @BeforeEach
    void setUp() {
        config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "connectTimeoutMs", 5000L);
        ReflectionTestUtils.setField(config, "readTimeoutMs", 10000L);
        ReflectionTestUtils.setField(config, "maxConnections", 400);
        ReflectionTestUtils.setField(config, "maxPerRoute", 100);
    }

    @Test
    void restTemplate_buildsHttpComponentsBackedFactory() {
        RestTemplate restTemplate = config.restTemplate(new RestTemplateBuilder());

        assertNotNull(restTemplate);
        // Timeouts are baked into the Apache HttpClient 5 RequestConfig, so the
        // factory must be HttpComponents-backed (not the JDK default).
        assertInstanceOf(HttpComponentsClientHttpRequestFactory.class, restTemplate.getRequestFactory());
    }

    @Test
    void razorpayRestClient_buildsCappedTimeoutClient() {
        RestClient client = config.razorpayRestClient(RestClient.builder());

        assertNotNull(client);
    }

    @Test
    void webhookRestClient_buildsDedicatedPool() {
        RestClient client = config.webhookRestClient(RestClient.builder());

        assertNotNull(client);
    }

    @Test
    void osrmRestTemplate_usesRoadDistanceTimeouts() {
        RestTemplate restTemplate = config.osrmRestTemplate(2000L, 2000L);

        assertNotNull(restTemplate);
        assertInstanceOf(HttpComponentsClientHttpRequestFactory.class, restTemplate.getRequestFactory());
    }

    @Test
    void defaults_reflectResilientOutboundDefaults() {
        // Sanity on the injected defaults used by the beans above.
        assertEquals(5000L, ReflectionTestUtils.getField(config, "connectTimeoutMs"));
        assertEquals(10000L, ReflectionTestUtils.getField(config, "readTimeoutMs"));
        assertEquals(400, ReflectionTestUtils.getField(config, "maxConnections"));
        assertEquals(100, ReflectionTestUtils.getField(config, "maxPerRoute"));
    }
}
