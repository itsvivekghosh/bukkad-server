package com.bhukkad.admin.support.config;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.ServiceJwtAuthFilter;
import com.bhukkad.common.web.SecurityHeadersFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

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
 */
@SpringBootTest(
        classes = SupportSecurityConfigTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SupportSecurityConfigTest {

    @TestConfiguration
    @Import(SupportSecurityConfig.class)
    static class FilterBeans {
        /** Chain-forwarding filter mocks: the security chain must run to completion. */
        private static <F extends jakarta.servlet.Filter> F passthrough(Class<F> type) {
            return Mockito.mock(type, invocation -> {
                if (invocation.getMethod().getName().equals("doFilter")) {
                    FilterChain chain = invocation.getArgument(2);
                    chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                }
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
        }

        @Bean
        PlatformJwtAuthFilter platformJwtAuthFilter() {
            return passthrough(PlatformJwtAuthFilter.class);
        }

        // Distinct bean name: platform-lib's ServiceAuthAutoConfiguration already
        // publishes a bean called "serviceJwtAuthFilter", and a same-named
        // @Bean here aborted context startup with BeanDefinitionOverrideException.
        @Bean
        ServiceJwtAuthFilter testServiceJwtAuthFilter() {
            return passthrough(ServiceJwtAuthFilter.class);
        }
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
    @Import({SecurityHeadersFilter.class, FilterBeans.class})
    static class TestApp {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Autowired
    private SecurityHeadersFilter securityHeadersFilter;

    @Autowired
    private PlatformJwtAuthFilter platformJwtAuthFilter;

    @Autowired
    @Qualifier("testServiceJwtAuthFilter")
    private ServiceJwtAuthFilter serviceJwtAuthFilter;

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
