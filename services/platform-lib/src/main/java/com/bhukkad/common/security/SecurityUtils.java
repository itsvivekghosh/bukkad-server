package com.bhukkad.common.security;

import com.bhukkad.common.error.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Shared principal accessor for every service (feature #6): the single way a
 * controller/service resolves "who is calling" from the security context.
 *
 * <p>Modules without their own accessor (order, personalization, growth, …)
 * were directed to use these static helpers so ownership checks do not drift
 * between codebases. The user id comes from the JWT subject bound by
 * {@link PlatformJwtAuthFilter}; admin detection accepts either the JWT
 * {@code scope}/{@code role} claim or a {@code ROLE_ADMIN} authority.</p>
 */
public final class SecurityUtils {

    public static final String ADMIN_SCOPE = "ADMIN";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private SecurityUtils() {
    }

    /** The authenticated platform principal, or empty when unauthenticated. */
    public static Optional<TokenPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof TokenPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /**
     * The authenticated user id (JWT subject).
     *
     * @throws UnauthorizedException when there is no authenticated platform principal
     */
    public static Long currentUserId() {
        return currentPrincipal()
                .map(TokenPrincipal::userId)
                .filter(java.util.Objects::nonNull)
                .orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }

    /** The authenticated user id, or {@code null} when unauthenticated (no throw). */
    public static Long currentUserIdOrNull() {
        return currentPrincipal().map(TokenPrincipal::userId).orElse(null);
    }

    /** True when the current caller holds the ADMIN role (claim or authority). */
    public static boolean isAdmin() {
        if (currentPrincipal()
                .map(principal -> ADMIN_SCOPE.equalsIgnoreCase(principal.scope() == null ? "" : principal.scope()))
                .orElse(false)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ROLE_ADMIN.equalsIgnoreCase(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
