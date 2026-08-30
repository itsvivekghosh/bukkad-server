package com.bhukkad.security;

import com.bhukkad.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final AccountLookupService accountLookupService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        String token = extractToken(servletRequest.getServletRequest());
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            return false;
        }

        String email = jwtTokenProvider.extractUsername(token);
        User user = accountLookupService.byEmail(email).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getActive())) {
            return false;
        }

        attributes.put("user", user);
        attributes.put("userId", user.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    private String extractToken(HttpServletRequest request) {
        // 1. Standard Authorization header (preferred, not logged by nginx)
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        // 2. Sec-WebSocket-Protocol header: client can send "Bearer, <token>" or just "<token>"
        //    Browsers allow custom subprotocols without leaking to access logs.
        String protocol = request.getHeader("Sec-WebSocket-Protocol");
        if (protocol != null && !protocol.isBlank()) {
            // Header may be "Bearer, <token>" or "bearer,<token>" — extract last segment
            String[] parts = protocol.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("Bearer ")) {
                    return trimmed.substring(7).trim();
                }
                // If the protocol itself is the token (no Bearer prefix), accept it if it looks like JWT
                if (trimmed.contains(".") && trimmed.length() > 20) {
                    return trimmed;
                }
            }
        }
        // 3. Legacy query param fallback — deprecated because nginx $request logs it.
        //    Kept for backward compat but will be removed. Token in URL also risks
        //    browser history and referer leakage.
        String queryToken = request.getParameter("token");
        if (queryToken != null) {
            org.slf4j.LoggerFactory.getLogger(JwtHandshakeInterceptor.class)
                    .warn("WS token via query param is deprecated; use Authorization or Sec-WebSocket-Protocol header");
        }
        return queryToken;
    }
}
