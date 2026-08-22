package com.bhukkad.apikey;

import com.bhukkad.exception.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates partner integrations via the {@code X-API-Key} header.
 * A valid key maps to an authenticated principal with the PARTNER authority
 * so downstream {@code @PreAuthorize("hasRole('PARTNER')")} checks apply.
 * Requests without an API key pass through untouched (JWT path still runs).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String ROLE_PARTNER = "PARTNER";

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            var key = apiKeyService.validate(apiKey.trim());
            if (key.isEmpty()) {
                log.warn("API_KEY_INVALID | uri={}", request.getRequestURI());
                throw new UnauthorizedException("Invalid or expired API key");
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "partner-" + key.get().getPartnerId(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + ROLE_PARTNER))));
        }
        filterChain.doFilter(request, response);
    }
}
