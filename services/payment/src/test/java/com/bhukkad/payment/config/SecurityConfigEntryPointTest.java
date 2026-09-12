package com.bhukkad.payment.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigEntryPointTest {

    @Test
    void restEntryPoint_rendersJsonUnauthorizedEnvelope() throws Exception {
        AuthenticationEntryPoint entryPoint =
                new SecurityConfig().restAuthenticationEntryPoint();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException failure =
                new org.springframework.security.authentication.BadCredentialsException(
                        "missing bearer");

        entryPoint.commence(request, response, failure);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
        assertThat(HttpStatus.UNAUTHORIZED.value()).isEqualTo(401);
        assertThat(new UsernamePasswordAuthenticationToken("a", "b").getPrincipal()).isEqualTo("a");
    }
}
