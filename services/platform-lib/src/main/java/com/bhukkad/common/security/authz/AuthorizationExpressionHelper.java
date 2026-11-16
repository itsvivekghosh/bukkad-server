package com.bhukkad.common.security.authz;

import com.bhukkad.common.security.TokenPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Spring Security expression helper for method-security annotations.
 *
 * <p>Register the bean as {@code authz} and use it like:</p>
 * <pre>{@code
 * @PreAuthorize("@authz.check('order-ownership', 'read', 'order', #orderId, #ownerId)")
 * }</pre>
 *
 * <p>This class is the only new platform class coupled to Spring Security.
 * The dependency remains optional in {@code platform-lib}; consumers that do
 * not use method security are not forced to add it.</p>
 */
@Component("authz")
public class AuthorizationExpressionHelper {

    private final AuthorizationService authorizationService;

    public AuthorizationExpressionHelper(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /** Convenience overload for the common owner-attribute decision. */
    public boolean check(String policyId,
                         String action,
                         String resourceType,
                         Object resourceId,
                         Object ownerId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (ownerId != null) {
            attributes.put(ResourceOwnershipPolicy.OWNER_ID_ATTRIBUTE, ownerId);
        }
        return check(policyId, action, resourceType, resourceId, attributes);
    }

    /** General overload for policies that need additional server-derived facts. */
    public boolean check(String policyId,
                         String action,
                         String resourceType,
                         Object resourceId,
                         Map<String, Object> attributes) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        TokenPrincipal principal = authentication != null
                && authentication.getPrincipal() instanceof TokenPrincipal tokenPrincipal
                ? tokenPrincipal
                : null;
        Set<String> roles = rolesOf(authentication);
        AuthorizationRequest request = new AuthorizationRequest(
                principal == null ? null : principal.userId(),
                roles,
                scopesOf(principal),
                action,
                resourceType,
                resourceId == null ? null : String.valueOf(resourceId),
                attributes);
        return authorizationService.isAllowed(policyId, request);
    }

    /** Direct request overload for callers that already built the ABAC input. */
    public boolean check(String policyId, AuthorizationRequest request) {
        return authorizationService.isAllowed(policyId, request);
    }

    private static Set<String> rolesOf(Authentication authentication) {
        if (authentication == null) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.regionMatches(true, 0, "ROLE_", 0, "ROLE_".length())) {
                roles.add(value.substring("ROLE_".length()));
            }
        }
        return roles;
    }

    private static Set<String> scopesOf(TokenPrincipal principal) {
        if (principal == null || principal.scope() == null || principal.scope().isBlank()) {
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : principal.scope().split("[\\s,]+")) {
            if (!scope.isBlank()) {
                scopes.add(scope);
            }
        }
        return scopes;
    }
}
