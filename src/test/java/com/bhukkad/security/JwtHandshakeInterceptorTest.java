package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtHandshakeInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WebSocketHandler webSocketHandler;

    @InjectMocks
    private JwtHandshakeInterceptor interceptor;

    @Test
    void beforeHandshake_rejectsNonServletRequest() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        assertFalse(interceptor.beforeHandshake(request, response, webSocketHandler, attributes));
    }

    @Test
    void beforeHandshake_acceptsValidBearerToken() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(new MockServletContext(), "GET", "/ws");
        servletRequest.addHeader("Authorization", "Bearer valid-token");
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        User user = activeUser(5L, "agent@test.com");
        when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("valid-token")).thenReturn("agent@test.com");
        when(userRepository.findByEmail("agent@test.com")).thenReturn(Optional.of(user));

        assertTrue(interceptor.beforeHandshake(request, response, webSocketHandler, attributes));
        assertEquals(user, attributes.get("user"));
        assertEquals(5L, attributes.get("userId"));
    }

    @Test
    void beforeHandshake_acceptsTokenQueryParameter() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(new MockServletContext(), "GET", "/ws");
        servletRequest.setParameter("token", "query-token");
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        User user = activeUser(3L, "owner@test.com");
        when(jwtTokenProvider.validateToken("query-token")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("query-token")).thenReturn("owner@test.com");
        when(userRepository.findByEmail("owner@test.com")).thenReturn(Optional.of(user));

        assertTrue(interceptor.beforeHandshake(request, response, webSocketHandler, attributes));
    }

    @Test
    void beforeHandshake_rejectsInvalidToken() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(new MockServletContext(), "GET", "/ws");
        servletRequest.addHeader("Authorization", "Bearer bad-token");
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);

        assertFalse(interceptor.beforeHandshake(request, response, webSocketHandler, new HashMap<>()));
    }

    @Test
    void beforeHandshake_rejectsInactiveUser() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(new MockServletContext(), "GET", "/ws");
        servletRequest.addHeader("Authorization", "Bearer valid-token");
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        User user = activeUser(1L, "inactive@test.com");
        user.setActive(false);
        when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("valid-token")).thenReturn("inactive@test.com");
        when(userRepository.findByEmail("inactive@test.com")).thenReturn(Optional.of(user));

        assertFalse(interceptor.beforeHandshake(request, response, webSocketHandler, new HashMap<>()));
    }

    @Test
    void afterHandshake_isNoOp() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(new MockServletContext(), "GET", "/ws");
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        assertDoesNotThrow(() ->
                interceptor.afterHandshake(request, response, webSocketHandler, null));
    }

    private static User activeUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setActive(true);
        return user;
    }
}
