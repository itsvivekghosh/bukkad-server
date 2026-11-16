package com.bhukkad.common.security.authz;

import com.bhukkad.common.security.TokenPrincipal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable input to an authorization policy.
 *
 * <p>The subject is the numeric user id from the validated platform JWT. Roles
 * and scopes are kept separately so policies can model either RBAC claims or
 * ABAC attributes without changing the request contract. Resource attributes
 * contain server-derived facts such as {@code ownerId}; callers must never
 * populate them from untrusted client input.</p>
 */
public record AuthorizationRequest(
        Long subject,
        Set<String> roles,
        Set<String> scopes,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> attributes) {

    public AuthorizationRequest {
        roles = immutableSet(roles);
        scopes = immutableSet(scopes);
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** Alias for callers that use the platform's user-id terminology. */
    public Long userId() {
        return subject;
    }

    public boolean hasRoleOrScope(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return roles.stream().anyMatch(value::equalsIgnoreCase)
                || scopes.stream().anyMatch(value::equalsIgnoreCase);
    }

    /** Builds a request from the existing platform JWT principal. */
    public static AuthorizationRequest fromPrincipal(TokenPrincipal principal,
                                                     String action,
                                                     String resourceType,
                                                     String resourceId,
                                                     Map<String, Object> attributes) {
        Long subject = principal == null ? null : principal.userId();
        Set<String> scopes = principal == null
                ? Set.of()
                : scopeValues(principal.scope());
        return new AuthorizationRequest(subject, Set.of(), scopes,
                action, resourceType, resourceId, attributes);
    }

    public AuthorizationRequest withAttributes(Map<String, Object> newAttributes) {
        return new AuthorizationRequest(subject, roles, scopes,
                action, resourceType, resourceId, newAttributes);
    }

    private static Set<String> scopeValues(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String value : scope.split("[\\s,]+")) {
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static Set<String> immutableSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                copy.add(value);
            }
        }
        return copy.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(copy);
    }
}
