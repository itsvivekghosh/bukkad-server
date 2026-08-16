package com.bhukkad.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class OpenAPIConfigTest {

    private OpenAPIConfig openAPIConfig;

    @BeforeEach
    void setUp() {
        openAPIConfig = new OpenAPIConfig();
        ReflectionTestUtils.setField(openAPIConfig, "serverPort", "9090");
    }

    @Test
    void customOpenAPI_buildsInfoServersSecurityAndTags() {
        OpenAPI openAPI = openAPIConfig.customOpenAPI();

        assertNotNull(openAPI);
        assertEquals("Bhukkad Food Delivery API", openAPI.getInfo().getTitle());
        assertEquals("1.0.0", openAPI.getInfo().getVersion());
        assertNotNull(openAPI.getInfo().getDescription());
        assertEquals("Bhukkad Support", openAPI.getInfo().getContact().getName());
        assertEquals("Apache 2.0", openAPI.getInfo().getLicense().getName());

        assertEquals(2, openAPI.getServers().size());
        assertEquals("http://localhost:9090", openAPI.getServers().get(0).getUrl());
        assertEquals("Local Development Server", openAPI.getServers().get(0).getDescription());
        assertEquals("https://api.bhukkad.com", openAPI.getServers().get(1).getUrl());

        SecurityScheme scheme = openAPI.getComponents().getSecuritySchemes().get("Bearer_JWT");
        assertNotNull(scheme);
        assertEquals(SecurityScheme.Type.HTTP, scheme.getType());
        assertEquals("bearer", scheme.getScheme());
        assertEquals("JWT", scheme.getBearerFormat());

        assertFalse(openAPI.getSecurity().isEmpty());
        assertTrue(openAPI.getSecurity().get(0).containsKey("Bearer_JWT"));
        assertEquals(9, openAPI.getTags().size());
        assertEquals("Authentication", openAPI.getTags().get(0).getName());
        assertEquals("Health", openAPI.getTags().get(8).getName());
    }
}
