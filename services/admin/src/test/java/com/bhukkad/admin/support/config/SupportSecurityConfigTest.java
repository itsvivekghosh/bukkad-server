package com.bhukkad.admin.support.config;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.ServiceJwtAuthFilter;
import com.bhukkad.common.web.SecurityHeadersFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real {@link SupportSecurityConfig} wiring: the filter-chain
 * bean builds, mesh/platform JWT filters are registered when available, and
 * unauthenticated traffic gets the JSON 401 contract from the custom
 * authentication entry point.
 *
 * <p>Uses a JPA-free test application (the production app class enables JPA
 * auditing, which the servlet-only slice has no metamodel for).</p>
 *
 * <p>The {@link PlatformJwtAuthFilter} and {@link ServiceJwtAuthFilter} beans
 * are mocked with {@code @MockBean} (not a {@code @TestConfiguration @Bean})
 * to prevent the mock from being auto-discovered by Spring Boot's
 * {@code SpringBootConfigurationFinder} and leaking into sibling
 * {@code @SpringBootTest} contexts (e.g. {@code AdminApiE2ETest}), where the
 * real filter is needed for end-to-end JWT validation.</p>
 */
@SpringBootTest(
        classes = SupportSecurityConfigTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SupportSecurityConfigTest {

    @MockBean
    PlatformJwtAuthFilter platformJwtAuthFilter;

    @MockBean
    ServiceJwtAuthFilter serviceJwtAuthFilter;

    /**
     * Both mock filters must pass through to the rest of the security chain
     * (chain.doFilter) without doing real authentication — the test has no
     * JWT secret to verify against.
     */
    @BeforeEach
    void configureFilterMocks() throws Exception {
        // Both mock filters must pass through to the rest of the security chain
        // (chain.doFilter) without doing real authentication — the test has no
        // JWT secret to verify against.
        doAnswer(invocation -> {
            try {
                FilterChain chain = invocation.getArgument(2);
                chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        }).when(platformJwtAuthFilter).doFilter(any(), any(), any());

        doAnswer(invocation -> {
            try {
                FilterChain chain = invocation.getArgument(2);
                chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        }).when(serviceJwtAuthFilter).doFilter(any(), any(), any());
    }

    // NOTE: deliberately NOT @SpringBootConfiguration / @EnableAutoConfiguration.
    // A second @SpringBootConfiguration anywhere on the test classpath becomes the
    // context bootstrap for EVERY @SpringBootTest in the module, so the JPA /
    // DataSource / Flyway excludes declared here were applied to the real
    // admin tests too. They then failed with "No bean named 'entityManagerFactory'"
    // and the shared failure threshold marked every remaining context test as
    // broken. This slice names its classes explicitly on @SpringBootTest(classes=)
    // and imports the exact auto-configurations it needs via @ImportAutoConfiguration.
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
            org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
            org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class,
            org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
    @Import({SecurityHeadersFilter.class, SupportSecurityConfig.class})
    static class TestApp {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Autowired
    private SecurityHeadersFilter securityHeadersFilter;

    @Test
    void chainRegistersHeaderServiceAndPlatformFilters() {
        assertThat(securityFilterChain).isNotNull();
        int headers = securityFilterChain.getFilters().indexOf(securityHeadersFilter);
        int service = securityFilterChain.getFilters().indexOf(serviceJwtAuthFilter);
        int platform = securityFilterChain.getFilters().indexOf(platformJwtAuthFilter);
        // All three registered; mesh token filter must precede the user JWT filter.
        assertThat(headers).isNotNegative();
        assertThat(service).isNotNegative();
        assertThat(platform).isNotNegative();
        assertThat(service).isLessThan(platform);
    }

    @Test
    void unauthenticatedRequestGetsJsonUnauthorizedContract() throws Exception {
        mvc.perform(get("/api/v1/support/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void entryPointWritesJson401Directly() throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        new SupportSecurityConfig().supportAuthenticationEntryPoint().commence(
                new org.springframework.mock.web.MockHttpServletRequest(), response,
                new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }
}
