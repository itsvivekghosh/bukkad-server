package com.bhukkad.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
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
 *       JWT. When {@code app.auth.service.enforce-internal-paths} is true
 *       (the default in every service yml) the reject happens even when no
 *       shared secret is configured — a misconfigured deployment fails
 *       CLOSED, never open (feature #5).</li>
 *   <li><b>All other paths</b>: a valid service token authenticates the
 *       caller as a service principal with {@code ROLE_SERVICE}; requests
 *       without the header continue to normal user-JWT/public handling.</li>
 * </ul>
 *
 * <p>Every rejection increments {@code service_auth_rejected{reason}} with
 * {@code reason} ∈ {absent, invalid, forbidden, weakkey} so auth failures are
 * observable in production (audit V-15).</p>
 */
public class ServiceJwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceJwtAuthFilter.class);
    public static final String ROLE_SERVICE = "ROLE_SERVICE";
    static final String HEADER = "X-Service-Token";

    /** service_auth_rejected reason tags (audit V-15 observability contract). */
    public static final String REASON_ABSENT = "absent";
    public static final String REASON_INVALID = "invalid";
    public static final String REASON_FORBIDDEN = "forbidden";
    public static final String REASON_WEAK_KEY = "weakkey";

    private static final List<String> DEFAULT_INTERNAL_PATTERNS =
            List.of("/api/v1/internal/**", "/internal/**");

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final ServiceAuthProperties properties;
    private final List<String> internalPatterns;
    private final boolean enforceInternal;
    @Nullable
    private final MeterRegistry meterRegistry;

    public ServiceJwtAuthFilter(ServiceAuthProperties properties) {
        this(properties, (MeterRegistry) null);
    }

    public ServiceJwtAuthFilter(ServiceAuthProperties properties, @Nullable MeterRegistry meterRegistry) {
        this(properties, DEFAULT_INTERNAL_PATTERNS, true, meterRegistry);
    }

    ServiceJwtAuthFilter(ServiceAuthProperties properties, List<String> internalPatterns,
                         boolean enforceInternal, @Nullable MeterRegistry meterRegistry) {
        this.properties = properties;
        this.internalPatterns = internalPatterns;
        this.enforceInternal = enforceInternal;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (StringUtils.hasText(properties.getJwtSecret())) {
            return false; // secret configured: verify whenever a header is presented
        }
        // No secret configured: run ONLY to fail-closed on enforced internal
        // paths (reject-absent-token); every other request passes through to
        // normal user-JWT/public handling.
        return !(enforceInternal && properties.isEnforceInternalPaths() && isInternalPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String serviceToken = request.getHeader(HEADER);
        boolean internalPath = isInternalPath(request);

        if (!StringUtils.hasText(serviceToken)) {
            if (enforceInternal && internalPath && properties.isEnforceInternalPaths()) {
                recordRejection(REASON_ABSENT);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Service token required");
                return;
            }
            // No service token: continue to normal auth (user JWT or public endpoint)
            filterChain.doFilter(request, response);
            return;
        }

        if (!StringUtils.hasText(properties.getJwtSecret())) {
            // Token presented but this side has no verification secret —
            // fail closed instead of trusting an unverifiable header.
            recordRejection(REASON_WEAK_KEY);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Service token cannot be verified");
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
                recordRejection(REASON_FORBIDDEN);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Service not allowed");
                return;
            }

            // Set authentication in context with a dedicated service authority
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    serviceId, null, List.of(new SimpleGrantedAuthority(ROLE_SERVICE)));
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            filterChain.doFilter(request, response);
        } catch (io.jsonwebtoken.security.WeakKeyException e) {
            log.warn("Service JWT secret too weak to verify tokens | path={}", request.getRequestURI());
            SecurityContextHolder.clearContext();
            recordRejection(REASON_WEAK_KEY);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service token");
        } catch (Exception e) {
            log.warn("Invalid service token | path={} | error={}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
            recordRejection(REASON_INVALID);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service token");
        }
    }

    private void recordRejection(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("service_auth_rejected", "reason", reason).increment();
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
