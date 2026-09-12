package com.bhukkad.search.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 401 contract for anonymous callers hitting a protected matcher: a JSON
 * envelope with the platform error shape — never a redirect to a login page.
 */
class SearchSecurityConfigEntryPointTest {

    @Test
    void entryPoint_writesJsonUnauthorizedEnvelope() throws Exception {
        AuthenticationEntryPoint entryPoint =
                new SearchSecurityConfig().restAuthenticationEntryPoint();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response,
                new AuthenticationException("anonymous") {
                });

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
    }
}
