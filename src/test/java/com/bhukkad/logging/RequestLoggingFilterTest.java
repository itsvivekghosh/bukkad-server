package com.bhukkad.logging;

import com.bhukkad.entity.User;
import com.bhukkad.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestLoggingFilterTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private FilterChain filterChain;

    private RequestLoggingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter(userRepository);
        ReflectionTestUtils.setField(filter, "debugMode", false);
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void skipsSwaggerAndActuator() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(response.getHeader("X-Trace-Id"));
    }

    @Test
    void logsRequestAndResponse_withJsonBodyAndAuth() throws Exception {
        ReflectionTestUtils.setField(filter, "debugMode", true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        request.setContentType("application/json");
        request.setContent("{\"name\":\"Ada\",\"password\":\"secret\"}".getBytes());
        request.addParameter("q", "one");
        request.addParameter("tags", "a", "b");
        request.addHeader("Authorization", "Bearer 1234567890abcdefghij");
        request.addHeader("X-Forwarded-For", "10.0.0.8, 10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        User user = new User();
        user.setId(7L);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ada@example.com", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        doAnswer(inv -> {
            CachedBodyHttpServletResponse wrapped = inv.getArgument(1);
            wrapped.setStatus(200);
            wrapped.setContentType("application/json");
            wrapped.getWriter().write("{\"ok\":true}");
            wrapped.flushBuffer();
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        assertNotNull(response.getHeader("X-Trace-Id"));
        assertNotNull(response.getHeader("X-Request-Id"));
        assertNotNull(response.getHeader("X-Timestamp"));
        assertNull(MDC.get(LoggingConstants.TRACE_ID));
    }

    @Test
    void logsBinaryBodiesAndNonJsonFallback() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/upload");
        request.setContentType("image/png");
        request.setContent("binary".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        doAnswer(inv -> {
            CachedBodyHttpServletResponse wrapped = inv.getArgument(1);
            wrapped.setStatus(201);
            wrapped.setContentType("application/pdf");
            wrapped.getOutputStream().write("pdf".getBytes());
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);
        assertEquals(201, response.getStatus());
    }

    @Test
    void logsNonJsonSanitizedBodies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/form");
        request.setContentType("text/plain");
        request.setContent("not-json".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        doAnswer(inv -> {
            CachedBodyHttpServletResponse wrapped = inv.getArgument(1);
            wrapped.setStatus(400);
            wrapped.setContentType("text/plain");
            wrapped.getWriter().write("plain-error");
            wrapped.flushBuffer();
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);
        assertEquals(400, response.getStatus());
    }

    @Test
    void logsServerErrorAndUsesRemoteAddr() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fail");
        request.setRemoteAddr("192.168.1.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        doAnswer(inv -> {
            CachedBodyHttpServletResponse wrapped = inv.getArgument(1);
            wrapped.setStatus(500);
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);
        assertEquals(500, response.getStatus());
    }

    @Test
    void populateUserContext_handlesAnonymousAndBrokenAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", "x", List.of()));

        doAnswer(inv -> {
            ((CachedBodyHttpServletResponse) inv.getArgument(1)).setStatus(204);
            return null;
        }).when(filterChain).doFilter(any(), any());
        filter.doFilterInternal(request, response, filterChain);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", "x", List.of()));
        when(userRepository.findByEmail("user@example.com")).thenThrow(new RuntimeException("db down"));
        filter.doFilterInternal(request, response, filterChain);
    }

    @Test
    void incomingAndCompletedLog_fallbackWhenMapperFails() throws Exception {
        ObjectMapper failing = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                throw new RuntimeException("json fail");
            }

            @Override
            public <T> T readValue(String content, Class<T> valueType) {
                throw new RuntimeException("parse fail");
            }
        };
        ReflectionTestUtils.setField(filter, "objectMapper", failing);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");
        request.setContentType("application/json");
        request.setContent("{\"a\":1}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        doAnswer(inv -> {
            CachedBodyHttpServletResponse wrapped = inv.getArgument(1);
            wrapped.setStatus(200);
            wrapped.setContentType("application/json");
            wrapped.getWriter().write("{\"b\":2}");
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);
    }

    @Test
    void performanceLevels_andStatusTexts() throws Exception {
        assertEquals("OK", invokeStatus(200));
        assertEquals("Created", invokeStatus(201));
        assertEquals("No Content", invokeStatus(204));
        assertEquals("Bad Request", invokeStatus(400));
        assertEquals("Unauthorized", invokeStatus(401));
        assertEquals("Forbidden", invokeStatus(403));
        assertEquals("Not Found", invokeStatus(404));
        assertEquals("Method Not Allowed", invokeStatus(405));
        assertEquals("Conflict", invokeStatus(409));
        assertEquals("Internal Server Error", invokeStatus(500));
        assertEquals("Bad Gateway", invokeStatus(502));
        assertEquals("Service Unavailable", invokeStatus(503));
        assertEquals("Unknown", invokeStatus(418));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/perf");
        ReflectionTestUtils.invokeMethod(filter, "logPerformance", request, 4000L);
        ReflectionTestUtils.invokeMethod(filter, "logPerformance", request, 1500L);
        ReflectionTestUtils.invokeMethod(filter, "logPerformance", request, 600L);
        ReflectionTestUtils.invokeMethod(filter, "logPerformance", request, 100L);

        ObjectMapper failing = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                throw new RuntimeException("fail");
            }
        };
        ReflectionTestUtils.setField(filter, "objectMapper", failing);
        ReflectionTestUtils.invokeMethod(filter, "logPerformance", request, 100L);
    }

    @Test
    void getClientIp_checksAllHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "unknown");
        request.addHeader("Proxy-Client-IP", "");
        request.addHeader("WL-Proxy-Client-IP", "UNKNOWN");
        request.addHeader("HTTP_X_FORWARDED_FOR", "2.2.2.2");
        assertEquals("2.2.2.2", ReflectionTestUtils.invokeMethod(filter, "getClientIpAddress", request));

        MockHttpServletRequest empty = new MockHttpServletRequest();
        empty.setRemoteAddr("9.9.9.9");
        assertEquals("9.9.9.9", ReflectionTestUtils.invokeMethod(filter, "getClientIpAddress", empty));
    }

    @Test
    void logIncomingRequest_nullHeadersAndNullBody() throws Exception {
        ReflectionTestUtils.setField(filter, "debugMode", true);
        HttpServletRequest raw = mock(HttpServletRequest.class);
        when(raw.getInputStream()).thenReturn(new jakarta.servlet.ServletInputStream() {
            @Override public boolean isFinished() { return true; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
            @Override public int read() { return -1; }
        });
        when(raw.getMethod()).thenReturn("GET");
        when(raw.getRequestURI()).thenReturn("/api/x");
        when(raw.getProtocol()).thenReturn("HTTP/1.1");
        when(raw.getRemoteAddr()).thenReturn("127.0.0.1");
        when(raw.getParameterMap()).thenReturn(Map.of());
        when(raw.getHeaderNames()).thenReturn(null);
        when(raw.getRequestURL()).thenReturn(new StringBuffer("http://localhost/api/x"));
        when(raw.getContentType()).thenReturn("application/json");
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(raw) {
            @Override
            public String getBody() {
                return null;
            }
        };
        ReflectionTestUtils.invokeMethod(filter, "logIncomingRequest", wrapped, "t", "r");
    }

    @Test
    void populateUserContext_unauthenticatedIsIgnored() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", "x"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doAnswer(inv -> {
            ((CachedBodyHttpServletResponse) inv.getArgument(1)).setStatus(200);
            return null;
        }).when(filterChain).doFilter(any(), any());
        filter.doFilterInternal(request, response, filterChain);
    }

    @Test
    void skipPatterns_coverAllEntries() {
        for (String uri : List.of("/swagger-ui", "/v3/api-docs", "/api-docs", "/actuator", "/favicon.ico", "/webjars")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            assertEquals(true, ReflectionTestUtils.invokeMethod(filter, "shouldSkip", request));
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        assertEquals(false, ReflectionTestUtils.invokeMethod(filter, "shouldSkip", request));
    }

    @Test
    void completedRequest_debugHeadersAndPerfLevels() throws Exception {
        ReflectionTestUtils.setField(filter, "debugMode", true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        MockHttpServletResponse raw = new MockHttpServletResponse();
        CachedBodyHttpServletResponse wrappedResponse = new CachedBodyHttpServletResponse(raw);
        wrappedResponse.setStatus(200);
        wrappedResponse.setHeader("Set-Cookie", "sid=abc");
        MDC.put(LoggingConstants.USER_ID, "1");
        MDC.put(LoggingConstants.USER_EMAIL, "a@b.com");
        MDC.put(LoggingConstants.USER_ROLE, "CUSTOMER");

        ReflectionTestUtils.invokeMethod(filter, "logCompletedRequest", wrappedRequest, wrappedResponse, 4000L, "t", "r");
        ReflectionTestUtils.invokeMethod(filter, "logCompletedRequest", wrappedRequest, wrappedResponse, 1500L, "t", "r");
        ReflectionTestUtils.invokeMethod(filter, "logCompletedRequest", wrappedRequest, wrappedResponse, 600L, "t", "r");
        ReflectionTestUtils.invokeMethod(filter, "logCompletedRequest", wrappedRequest, wrappedResponse, 100L, "t", "r");

        CachedBodyHttpServletResponse emptyHeaders = new CachedBodyHttpServletResponse(new MockHttpServletResponse()) {
            @Override
            public String getBody() {
                return null;
            }
        };
        emptyHeaders.setStatus(200);
        ReflectionTestUtils.invokeMethod(filter, "logCompletedRequest", wrappedRequest, emptyHeaders, 50L, "t", "r");
    }

    private String invokeStatus(int code) {
        return (String) ReflectionTestUtils.invokeMethod(filter, "getStatusText", code);
    }
}
