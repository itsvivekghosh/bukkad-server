package com.bhukkad.personalization.config;

import com.bhukkad.personalization.AbstractPersonalizationPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.SecurityFilterChain;
import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.ServiceJwtAuthFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code PersonalizationSecurityConfig}: with the mesh secret set
 * both JWT filters are registered on the chain, and the JSON 401 entry point
 * answers anonymous calls to the self-scoped recommendation surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.service.jwt-secret=0123456789abcdef0123456789abcdef",
                "app.auth.service.allowed-services=identity,growth"
        })
class PersonalizationSecurityConfigPostgresTest extends AbstractPersonalizationPostgresTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Autowired
    private ApplicationContext context;

    @Test
    void meshAndPlatformFiltersAreRegistered() {
        assertThat(context.getBean(ServiceJwtAuthFilter.class)).isNotNull();
        assertThat(securityFilterChain.getFilters())
                .anyMatch(f -> f instanceof PlatformJwtAuthFilter)
                .anyMatch(f -> f instanceof ServiceJwtAuthFilter)
                .anyMatch(f -> f instanceof com.bhukkad.common.web.SecurityHeadersFilter);
    }

    @Test
    void anonymousReorderCall_returns401JsonEntryPoint() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/customers/me/recommendations/reorder", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void healthIsPublic() {
        ResponseEntity<String> response = rest.getForEntity("/health/ping", String.class);

        assertThat(response.getStatusCode().value()).isBetween(200, 299);
        assertThat(response.getBody()).contains("pong");
    }
}
