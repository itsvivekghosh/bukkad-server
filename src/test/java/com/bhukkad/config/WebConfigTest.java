package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebConfigTest {

    @Test
    void addCorsMappings_configuresApiCors() {
        WebConfig webConfig = new WebConfig();
        // Batch D: CORS origins are now read from application properties rather
        // than hard-coded, so inject the default value via reflection for this
        // plain-unit-test instantiation.
        org.springframework.test.util.ReflectionTestUtils.setField(
                webConfig, "allowedOrigins", "http://localhost:3000,http://localhost:4200");

        CorsRegistry registry = new CorsRegistry();

        webConfig.addCorsMappings(registry);

        @SuppressWarnings("unchecked")
        Map<String, CorsConfiguration> configs = (Map<String, CorsConfiguration>)
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
        assertTrue(configs.containsKey("/api/**"));
        CorsConfiguration cors = configs.get("/api/**");
        assertEquals(List.of("http://localhost:3000", "http://localhost:4200"), cors.getAllowedOrigins());
        assertEquals(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"), cors.getAllowedMethods());
        assertEquals(List.of("*"), cors.getAllowedHeaders());
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        assertEquals(3600L, cors.getMaxAge());
    }
}
