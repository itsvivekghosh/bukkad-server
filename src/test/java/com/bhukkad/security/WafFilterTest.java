package com.bhukkad.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class WafFilterTest {

    @Mock
    private FilterChain filterChain;

    private WafFilter wafFilter;

    @BeforeEach
    void setUp() {
        wafFilter = new WafFilter();
    }

    private MockHttpServletResponse doFilter(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        wafFilter.doFilter(request, response, filterChain);
        return response;
    }

    @Test
    void cleanRequest_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.addParameter("keyword", "pizza");

        doFilter(request);

        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sqliTautology_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("name", "foo' OR '1'='1");

        MockHttpServletResponse response = doFilter(request);
        assertEquals(400, response.getStatus());
        verifyNoInteractions(filterChain);
    }

    @Test
    void sqliUnionSelect_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("q", "x UNION SELECT password FROM users");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void sqliComment_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("id", "1--");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void xssScriptTag_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addParameter("comment", "<script>alert(1)</script>");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void xssJavascriptUri_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("url", "javascript:alert(1)");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void longValue_ignored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.addParameter("q", "a".repeat(5000));

        doFilter(request);

        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private MockHttpServletRequest jsonRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void jweEncryptedPassword_withSqlLikeCiphertext_passesThrough() throws ServletException, IOException {
        // JWE ciphertext is base64url: it can legitimately contain "--", "/*" or
        // ";" sequences that match SQLi patterns. The WAF must not block valid
        // auth requests because the encrypted blob happens to look malicious.
        String jwe = "eyJhbGciOiJSU0EtT0FFUC0yNTYifQ.eyJwYXNzd29yZCI6InRlc3QifQ."
                + "abc--def/*ghi;ij==.sig";
        MockHttpServletRequest request = jsonRequest(
                "{\"email\":\"user@example.com\",\"encryptedPassword\":\"" + jwe + "\"}");

        doFilter(request);

        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void jweEncryptedPassword_injectionStillBlocked() throws ServletException, IOException {
        // A real SQLi in a NON-encrypted field must still be blocked even when
        // an encryptedPassword field is present.
        MockHttpServletRequest request = jsonRequest(
                "{\"email\":\"x' OR '1'='1\",\"encryptedPassword\":\"eyJhbGciOiJSU0EtT0FFUC0yNTYifQ.sig\"}");

        assertEquals(400, doFilter(request).getStatus());
        verifyNoInteractions(filterChain);
    }

    @Test
    void jweEncryptedPassword_unionSelectStillBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = jsonRequest(
                "{\"fullName\":\"x UNION SELECT password FROM users\","
                        + "\"encryptedPassword\":\"eyJhbGciOiJSU0EtT0FFUC0yNTYifQ.sig\"}");

        assertEquals(400, doFilter(request).getStatus());
    }
}
