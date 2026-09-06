package com.bhukkad.common.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * Authorization helpers shared by every service's controllers.
 *
 * <p>Centralizes the "subject must match the resource owner, unless the caller
 * is an admin" rule so it cannot drift between controllers.</p>
 */
public final class PrincipalGuard {

    public static final String SCOPE_ADMIN = "ADMIN";

    private PrincipalGuard() {
    }

    /**
     * Ensures a principal exists (401-equivalent otherwise) and either owns
     * the addressed resource or holds the ADMIN scope.
     */
    public static void requireSelfOrAdmin(TokenPrincipal principal, Long resourceOwnerId) {
        requireAuthenticated(principal);
        boolean admin = SCOPE_ADMIN.equalsIgnoreCase(safeScope(principal));
        if (!admin && !resourceOwnerId.equals(principal.userId())) {
            throw new AccessDeniedException("Cannot act on another customer's resource");
        }
    }

    /**
     * Ensures the caller holds the given scope (e.g. ADMIN).
     */
    public static void requireRole(TokenPrincipal principal, String scope) {
        requireAuthenticated(principal);
        if (!scope.equalsIgnoreCase(safeScope(principal))) {
            throw new AccessDeniedException("Insufficient permissions");
        }
    }

    public static void requireAdmin(TokenPrincipal principal) {
        requireRole(principal, SCOPE_ADMIN);
    }

    public static void requireAuthenticated(TokenPrincipal principal) {
        if (principal == null) {
            // Method-security guard reached without authentication (permitAll
            // path or filter disabled): answer as unauthenticated, never 500.
            throw new AccessDeniedException("Authentication required");
        }
    }

    public static boolean isAdmin(TokenPrincipal principal) {
        return principal != null && SCOPE_ADMIN.equalsIgnoreCase(safeScope(principal));
    }

    private static String safeScope(TokenPrincipal principal) {
        return principal.scope() == null ? "" : principal.scope();
    }
}
