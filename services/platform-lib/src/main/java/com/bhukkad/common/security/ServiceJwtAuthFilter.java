package com.bhukkad.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Filter for service-to-service JWT authentication.
 *
 * <p>Validates JWT tokens in the {@code X-Service-Token} header. Two behaviors
 * depending on the request path:</p>
 * <ul>
 *   <li><b>Internal paths</b> (default {@code /api/v1/internal/**} and
 *       {@code /internal/**}): REQUIRE a valid service token — requests
 *       without one are rejected with 401. This closes the hole where
 *       internal money/PII endpoints were reachable with any ordinary user
 *       JWT.</li>
 *   <li><b>All other paths</b>: a valid service token authenticates the
 *       caller as a service principal with {@code ROLE_SERVICE}; requests
 *       without the header continue to normal user-JWT/public handling.</li>
 * </ul>
 *
 * <p>When {@code app.auth.service.jwt-secret} is unset the filter passes
 * everything through (local development without the service mesh); in
 * production {@code SecretValidationConfig} requires the secret to be set.</p>
 */
public class ServiceJwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceJwtAuthFilter.class);
    public static final String ROLE_SERVICE = "ROLE_SERVICE";
    static final String HEADER = "X-Service-Token";
    private static final List<String> DEFAULT_INTERNAL_PATTERNS =
            List.of("/api/v1/internal/**", "/internal/**");

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final ServiceAuthProperties properties;
    private final List<String> internalPatterns;
    private final boolean enforceInternal;

    public ServiceJwtAuthFilter(ServiceAuthProperties properties) {
        this(properties, DEFAULT_INTERNAL_PATTERNS, true);
    }

    ServiceJwtAuthFilter(ServiceAuthProperties properties, List<String> internalPatterns,
                         boolean enforceInternal) {
        this.properties = properties;
        this.internalPatterns = internalPatterns;
        this.enforceInternal = enforceInternal;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Nothing to do when service auth is not configured at all.
        return !StringUtils.hasText(properties.getJwtSecret());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String serviceToken = request.getHeader(HEADER);
        boolean internalPath = isInternalPath(request);

        if (!StringUtils.hasText(serviceToken)) {
            if (enforceInternal && internalPath && properties.isEnforceInternalPaths()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Service token required");
                return;
            }
            // No service token: continue to normal auth (user JWT or public endpoint)
            filterChain.doFilter(request, response);
            return;
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(serviceToken)
                    .getPayload();

            String serviceId = claims.getSubject();
            if (serviceId == null || !isAllowedService(serviceId)) {
                log.warn("Rejected service token from disallowed subject={}", serviceId);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Service not allowed");
                return;
            }

            // Set authentication in context with a dedicated service authority
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    serviceId, null, List.of(new SimpleGrantedAuthority(ROLE_SERVICE)));
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("Invalid service token | path={} | error={}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service token");
        }
    }

    private boolean isInternalPath(HttpServletRequest request) {
        if (!enforceInternal || request.getMethod() == HttpMethod.OPTIONS.name()) {
            return false;
        }
        String path = request.getRequestURI();
        return internalPatterns.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private boolean isAllowedService(String serviceId) {
        if (!StringUtils.hasText(properties.getAllowedServices())) {
            return false;
        }
        return List.of(properties.getAllowedServices().split(","))
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(s -> s.equalsIgnoreCase(serviceId));
    }
}
