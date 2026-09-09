package com.bhukkad.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Populates the Spring Security context from the platform JWT.
 *
 * <p>The filter is registered unconditionally and is inert while
 * {@link PlatformJwtValidator#isEnabled()} is false (no secret/JWKS
 * configured): the surrounding {@code authenticated()} rule then rejects
 * requests with 401 — fail-closed, never fail-open. Gating the bean on the
 * {@code secret} property alone previously disabled authentication entirely
 * for JWKS-only (RS256) deployments.</p>
 */
@Component
public class PlatformJwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlatformJwtAuthFilter.class);

    public static final String MDC_USER_ID = "userId";
    public static final String MDC_USER_EMAIL = "userEmail";
    public static final String MDC_USER_ROLE = "userRole";

    private final PlatformJwtValidator validator;

    public PlatformJwtAuthFilter(PlatformJwtValidator validator) {
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            if (!validator.isEnabled()) {
                // No credentials configured: leave the context empty so the
                // security chain's authenticated() rule answers 401.
                filterChain.doFilter(request, response);
                return;
            }
            String token = bearerToken(request);
            if (StringUtils.hasText(token)) {
                validator.validate(token).ifPresent(principal -> {
                    List<SimpleGrantedAuthority> authorities = principal.scope() != null
                            ? List.of(new SimpleGrantedAuthority("ROLE_" + principal.scope().toUpperCase(Locale.ROOT)))
                            : List.of();
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    MDC.put(MDC_USER_ID, String.valueOf(principal.userId()));
                    MDC.put(MDC_USER_EMAIL, principal.email());
                    if (principal.scope() != null) {
                        MDC.put(MDC_USER_ROLE, "ROLE_" + principal.scope().toUpperCase(Locale.ROOT));
                    }
                    log.debug("Authenticated principal userId={} scope={}", principal.userId(), principal.scope());
                });
            }
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            log.debug("Could not authenticate request: {}", e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}