package com.bhukkad.support.config;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.ServiceJwtAuthFilter;
import com.bhukkad.common.web.SecurityHeadersFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
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

        @Bean
        ServiceJwtAuthFilter serviceJwtAuthFilter() {
            return passthrough(ServiceJwtAuthFilter.class);
        }
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"})
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
