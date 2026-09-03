package com.bhukkad.common.security;

import com.bhukkad.common.security.ServiceAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Filter for service-to-service JWT authentication.
 *
 * <p>Validates JWT tokens in the X-Service-Token header for internal
 * service-to-service calls. When APP_AUTH_SERVICE_JWT_SECRET is set,
 * only requests with valid service tokens are allowed.</p>
 */
public class ServiceJwtAuthFilter extends OncePerRequestFilter {

    private final ServiceAuthProperties properties;

    public ServiceJwtAuthFilter(ServiceAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String serviceToken = request.getHeader("X-Service-Token");

        if (serviceToken == null || serviceToken.isEmpty()) {
            // No service token: continue to normal auth (user JWT or public endpoint)
            filterChain.doFilter(request, response);
            return;
        }

        if (properties.getJwtSecret() == null || properties.getJwtSecret().isEmpty()) {
            // Service auth not configured: reject service token calls
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service authentication not configured");
            return;
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(serviceToken)
                    .getBody();

            String serviceId = claims.getSubject();
            if (serviceId == null || !isAllowedService(serviceId)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Service not allowed: " + serviceId);
                return;
            }

            // Set authentication in context
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(serviceId, null, List.of());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service token");
        }
    }

    private boolean isAllowedService(String serviceId) {
        if (properties.getAllowedServices() == null || properties.getAllowedServices().isEmpty()) {
            return true; // No restriction when not configured
        }
        return List.of(properties.getAllowedServices().split(","))
                .stream()
                .map(String::trim)
                .anyMatch(s -> s.equalsIgnoreCase(serviceId));
    }
}
