package com.bhukkad.security;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import com.bhukkad.security.AccountFields;
import com.bhukkad.security.AccountLookupService;
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
    private AccountLookupService accountLookupService;

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
        when(accountLookupService.byEmail("agent@test.com")).thenReturn(Optional.of(user));

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
        when(accountLookupService.byEmail("owner@test.com")).thenReturn(Optional.of(user));

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
        when(accountLookupService.byEmail("inactive@test.com")).thenReturn(Optional.of(user));

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

    @Test
    void beforeHandshake_acceptsTokenViaSecWebSocketProtocolBearerPrefix() {
        // Batch D: browsers cannot set an Authorization header on WebSocket;
        // the token arrives as "Bearer, <token>" in Sec-WebSocket-Protocol.
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(new MockServletContext(), "GET", "/ws");
        String wsJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ3cyJ0.protocol-signature";
        servletRequest.addHeader("Sec-WebSocket-Protocol", "Bearer, " + wsJwt);
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        User user = activeUser(6L, "ws@test.com");
        when(jwtTokenProvider.validateToken(wsJwt)).thenReturn(true);
        when(jwtTokenProvider.extractUsername(wsJwt)).thenReturn("ws@test.com");
        when(accountLookupService.byEmail("ws@test.com")).thenReturn(Optional.of(user));

        Map<String, Object> attributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(request, response, webSocketHandler, attributes));
        assertEquals(user, attributes.get("user"));
        assertEquals(6L, attributes.get("userId"));
    }

    @Test
    void beforeHandshake_acceptsRawJwtInSecWebSocketProtocol() {
        // Subprotocol sent as the bare token (no Bearer prefix) is accepted
        // when it looks like a JWT (contains a dot, long enough).
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(new MockServletContext(), "GET", "/ws");
        String rawJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature-part";
        servletRequest.addHeader("Sec-WebSocket-Protocol", rawJwt);
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        User user = activeUser(8L, "raw@test.com");
        when(jwtTokenProvider.validateToken(rawJwt)).thenReturn(true);
        when(jwtTokenProvider.extractUsername(rawJwt)).thenReturn("raw@test.com");
        when(accountLookupService.byEmail("raw@test.com")).thenReturn(Optional.of(user));

        Map<String, Object> attributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(request, response, webSocketHandler, attributes));
        assertEquals(user, attributes.get("user"));
    }

    private static User activeUser(Long id, String email) {
        // V62: credentials live on role tables; AccountFields reads need a
        // role-typed instance, so the fixture builds a Customer.
        Customer user = new Customer();
        user.setId(id);
        AccountFields.setEmail(user, email);
        user.setActive(true);
        return user;
    }
}
